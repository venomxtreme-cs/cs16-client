package su.xash.cs16client;

import android.os.Environment;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Installs a custom native module (.so) into the shared game tree and
 * auto-registers it so the engine actually loads it on the next start.
 *
 * Two kinds are supported:
 *
 *   AMXX module     -> addons/amxmodx/modules/libamxx_<name>_<abi>.so
 *                       + a bare <name> line appended to
 *                         addons/amxmodx/configs/modules.ini
 *
 *   Metamod plugin  -> addons/amxmodx/dlls/lib<name>_<abi>.so
 *                       + a "linux addons/amxmodx/dlls/lib<name>.so" line
 *                         appended to addons/metamod/plugins.ini
 *
 * Metamod plugins deliberately live under amxmodx/dlls (NOT metamod/dlls):
 * that directory is the one the engine's ABI filter strips the _arm64 /
 * _armv7a suffix from - see COM_AMXX_CopyTree in engine/common/lib_common.c.
 * So a single neutral plugins.ini line serves BOTH arm64 and arm32, exactly
 * how the AMX Mod X core itself (libmm_amxmodx.so) is loaded.
 *
 * Installed modules are recorded in <gamedir>/addons/.custom-modules so the
 * list survives app updates, and reapplyAll() re-appends the registration
 * lines after a bundle refresh rewrites modules.ini.
 */
public final class ModuleInstaller {
	private static final String TAG = "ModuleInstaller";

	public static final int KIND_AMXX = 0;
	public static final int KIND_METAMOD = 1;

	public static final String GAME_CSTRIKE = "cstrike";
	public static final String GAME_VALVE = "valve";

	private ModuleInstaller() {
	}

	public static File xashDir() {
		return new File( Environment.getExternalStorageDirectory(), "xash" );
	}

	// ------------------------------------------------------------------
	// results / entries
	// ------------------------------------------------------------------

	public static class Result {
		public final boolean ok;
		public final String message;

		Result( boolean ok, String message ) {
			this.ok = ok;
			this.message = message;
		}
	}

	public static class Entry {
		public final int kind;
		public final String base;
		public String abi;		// "arm64", "armv7", ... (filled by list())
		public boolean present;		// false when no .so is on disk

		Entry( int kind, String base ) {
			this.kind = kind;
			this.base = base;
		}
	}

	// ------------------------------------------------------------------
	// ABI detection - read the ELF header, no native code needed
	// ------------------------------------------------------------------

	/**
	 * Returns "arm64" or "armv7a", or null when the stream is not an ARM
	 * Android shared object. e_machine lives at offset 18..19 (little endian):
	 * EM_AARCH64 = 183, EM_ARM = 40; EI_CLASS (offset 4) is 2 for 64-bit.
	 */
	public static String detectAbi( InputStream in ) {
		try {
			byte[] h = new byte[20];
			int got = 0;
			while( got < 20 ) {
				int n = in.read( h, got, 20 - got );
				if( n <= 0 )
					break;
				got += n;
			}
			if( got < 20 )
				return null;
			if( h[0] != 0x7f || h[1] != 'E' || h[2] != 'L' || h[3] != 'F' )
				return null;
			int cls = h[4] & 0xff;			// 1 = 32-bit, 2 = 64-bit
			int machine = ( h[18] & 0xff ) | (( h[19] & 0xff ) << 8 );
			if( machine == 183 && cls == 2 )
				return "arm64";
			if( machine == 40 && cls == 1 )
				return "armv7a";
			return null;
		} catch( Throwable t ) {
			return null;
		}
	}

	public static String detectAbi( File f ) {
		try( InputStream in = new FileInputStream( f )) {
			return detectAbi( in );
		} catch( Throwable t ) {
			return null;
		}
	}

	// ------------------------------------------------------------------
	// base module name from a file name
	// ------------------------------------------------------------------

	/** Strips directories, ".so", a trailing ABI suffix, and lib/libamxx_. */
	static String baseName( String filename ) {
		String s = filename == null ? "" : filename.trim();
		int slash = Math.max( s.lastIndexOf( '/' ), s.lastIndexOf( '\\' ));
		if( slash >= 0 )
			s = s.substring( slash + 1 );
		String low = s.toLowerCase( Locale.US );
		if( low.endsWith( ".so" )) {
			s = s.substring( 0, s.length() - 3 );
			low = s.toLowerCase( Locale.US );
		}
		String[] suf = { "_arm64", "_armv7a", "_armv7l", "_armv7",
			"_x86_64", "_amd64", "_x86" };
		boolean changed = true;
		while( changed ) {
			changed = false;
			low = s.toLowerCase( Locale.US );
			for( String x : suf ) {
				if( s.length() > x.length() && low.endsWith( x )) {
					s = s.substring( 0, s.length() - x.length() );
					changed = true;
				}
			}
		}
		low = s.toLowerCase( Locale.US );
		if( low.startsWith( "libamxx_" ))
			s = s.substring( 8 );
		else if( low.startsWith( "lib" ))
			s = s.substring( 3 );
		return s.trim();
	}

	// ------------------------------------------------------------------
	// paths / naming
	// ------------------------------------------------------------------

	private static File destDir( String game, int kind ) {
		// metamod plugins go under amxmodx/dlls so the engine ABI-strips them
		// (see header). AMXX modules go under amxmodx/modules as usual.
		if( kind == KIND_AMXX )
			return new File( new File( xashDir(), game ), "addons/amxmodx/modules" );
		return new File( new File( xashDir(), game ), "addons/amxmodx/dlls" );
	}

	private static String destFileName( int kind, String base, String abi ) {
		if( kind == KIND_AMXX )
			return "libamxx_" + base + "_" + abi + ".so";
		return "lib" + base + "_" + abi + ".so";
	}

	private static File regFile( String game, int kind ) {
		if( kind == KIND_AMXX )
			return new File( new File( xashDir(), game ),
				"addons/amxmodx/configs/modules.ini" );
		return new File( new File( xashDir(), game ),
			"addons/metamod/plugins.ini" );
	}

	private static String regLine( int kind, String base ) {
		if( kind == KIND_AMXX )
			return base;
		return "linux addons/amxmodx/dlls/lib" + base + ".so";
	}

	private static String regHeader( int kind ) {
		return "; custom " + ( kind == KIND_AMXX ? "module" : "plugin" )
			+ " installed by CS16Client";
	}

	// ------------------------------------------------------------------
	// install
	// ------------------------------------------------------------------

	public static Result install( int kind, String game, File src ) {
		return install( kind, game, src, null );
	}

	/**
	 * baseOverride lets a caller that already knows the display name (a SAF
	 * picker) pass it straight through instead of relying on the temp name.
	 */
	public static Result install( int kind, String game, File src,
			String baseOverride ) {
		String abi = detectAbi( src );
		if( abi == null )
			return new Result( false,
				"not an ARM Android library (need arm64 or armeabi-v7a)" );

		String base = ( baseOverride != null && !baseOverride.isEmpty() )
			? baseName( baseOverride ) : baseName( src.getName() );
		if( base.isEmpty() )
			return new Result( false, "could not read a module name" );

		try {
			File dir = destDir( game, kind );
			dir.mkdirs();
			File dest = new File( dir, destFileName( kind, base, abi ));
			copyFile( src, dest );
			dest.setExecutable( true, false );

			appendLine( regFile( game, kind ), regHeader( kind ),
				regLine( kind, base ));
			addRegistry( game, kind, base );

			return new Result( true, base + " (" + abi + ") installed" );
		} catch( Throwable t ) {
			Log.w( TAG, "install failed", t );
			return new Result( false, "install error: " + t.getMessage() );
		}
	}

	/** Installs every .so found in the drop-in folders for the given game. */
	public static List<Result> installDropIn( String game ) {
		List<Result> out = new ArrayList<Result>();
		out.addAll( installDir(
			new File( xashDir(), "custom_modules/amxx" ), KIND_AMXX, game ));
		out.addAll( installDir(
			new File( xashDir(), "custom_modules/mod" ), KIND_METAMOD, game ));
		return out;
	}

	private static List<Result> installDir( File dir, int kind, String game ) {
		List<Result> out = new ArrayList<Result>();
		File[] files = dir.listFiles();
		if( files == null )
			return out;
		for( File f : files ) {
			if( !f.isFile() )
				continue;
			if( !f.getName().toLowerCase( Locale.US ).endsWith( ".so" ))
				continue;
			out.add( install( kind, game, f ));
		}
		return out;
	}

	// ------------------------------------------------------------------
	// list / remove
	// ------------------------------------------------------------------

	public static List<Entry> list( String game ) {
		List<Entry> out = readRegistry( game );
		for( Entry e : out ) {
			File dir = destDir( game, e.kind );
			File a64 = new File( dir, destFileName( e.kind, e.base, "arm64" ));
			File a32 = new File( dir, destFileName( e.kind, e.base, "armv7a" ));
			e.present = a64.isFile() || a32.isFile();
			e.abi = a64.isFile() && a32.isFile() ? "arm64+armv7"
				: a64.isFile() ? "arm64"
				: a32.isFile() ? "armv7a" : "-";
		}
		return out;
	}

	public static void remove( String game, Entry e ) {
		File dir = destDir( game, e.kind );
		new File( dir, destFileName( e.kind, e.base, "arm64" )).delete();
		new File( dir, destFileName( e.kind, e.base, "armv7a" )).delete();
		removeLine( regFile( game, e.kind ), regLine( e.kind, e.base ));

		List<Entry> all = readRegistry( game );
		try {
			File f = registryFile( game );
			PrintWriter pw = new PrintWriter( new FileWriter( f, false ));
			for( Entry x : all ) {
				if( x.kind == e.kind && x.base.equals( e.base ))
					continue;
				pw.println( x.kind + " " + x.base );
			}
			pw.close();
		} catch( Throwable t ) {
			Log.w( TAG, "registry rewrite failed", t );
		}
	}

	// ------------------------------------------------------------------
	// registry (survives app updates; used for list + re-apply)
	// ------------------------------------------------------------------

	private static File registryFile( String game ) {
		return new File( new File( xashDir(), game ), "addons/.custom-modules" );
	}

	private static void addRegistry( String game, int kind, String base ) {
		for( Entry e : readRegistry( game )) {
			if( e.kind == kind && e.base.equals( base ))
				return;
		}
		try {
			File f = registryFile( game );
			File parent = f.getParentFile();
			if( parent != null )
				parent.mkdirs();
			PrintWriter pw = new PrintWriter( new FileWriter( f, true ));
			pw.println( kind + " " + base );
			pw.close();
		} catch( Throwable t ) {
			Log.w( TAG, "registry add failed", t );
		}
	}

	static List<Entry> readRegistry( String game ) {
		List<Entry> out = new ArrayList<Entry>();
		File f = registryFile( game );
		if( !f.isFile() )
			return out;
		try {
			BufferedReader br = new BufferedReader( new FileReader( f ));
			String line;
			while(( line = br.readLine() ) != null ) {
				line = line.trim();
				if( line.isEmpty() || line.startsWith( "#" ))
					continue;
				String[] p = line.split( "\\s+" );
				if( p.length < 2 )
					continue;
				try {
					out.add( new Entry( Integer.parseInt( p[0] ), p[1] ));
				} catch( NumberFormatException ignored ) {
				}
			}
			br.close();
		} catch( Throwable t ) {
			Log.w( TAG, "registry read failed", t );
		}
		return out;
	}

	/**
	 * Re-appends every registered module's config line. Called after the
	 * bundle refresh rewrites modules.ini (which would otherwise drop a
	 * custom module's line on an app update). Idempotent: an existing line
	 * is left untouched.
	 */
	public static void reapplyAll() {
		String[] games = { GAME_CSTRIKE, GAME_VALVE };
		for( String game : games ) {
			for( Entry e : readRegistry( game )) {
				try {
					File dir = destDir( game, e.kind );
					File a64 = new File( dir, destFileName( e.kind, e.base, "arm64" ));
					File a32 = new File( dir, destFileName( e.kind, e.base, "armv7a" ));
					if( !a64.isFile() && !a32.isFile() )
						continue;	// removed outside the app
					appendLine( regFile( game, e.kind ), regHeader( e.kind ),
						regLine( e.kind, e.base ));
				} catch( Throwable ignored ) {
				}
			}
		}
	}

	// ------------------------------------------------------------------
	// small file helpers
	// ------------------------------------------------------------------

	private static void copyFile( File src, File dst ) throws Exception {
		File parent = dst.getParentFile();
		if( parent != null )
			parent.mkdirs();
		InputStream in = new FileInputStream( src );
		OutputStream out = new FileOutputStream( dst );
		try {
			byte[] buf = new byte[65536];
			int n;
			while(( n = in.read( buf )) > 0 )
				out.write( buf, 0, n );
		} finally {
			out.close();
			in.close();
		}
	}

	/** Appends header + line only when the line is not already present. */
	private static void appendLine( File ini, String header, String line )
			throws Exception {
		boolean present = false;
		if( ini.isFile() ) {
			BufferedReader br = new BufferedReader( new FileReader( ini ));
			String l;
			while(( l = br.readLine() ) != null ) {
				if( l.trim().equals( line )) {
					present = true;
					break;
				}
			}
			br.close();
		}
		if( present )
			return;

		File parent = ini.getParentFile();
		if( parent != null )
			parent.mkdirs();

		PrintWriter pw = new PrintWriter( new FileWriter( ini, true ));
		// keep the appended block on its own line even if the file had no
		// trailing newline
		if( ini.isFile() && ini.length() > 0 ) {
			RandomAccessFile raf = new RandomAccessFile( ini, "r" );
			raf.seek( ini.length() - 1 );
			int last = raf.read();
			raf.close();
			if( last != '\n' )
				pw.print( "\n" );
		}
		pw.print( header + "\n" );
		pw.print( line + "\n" );
		pw.close();
	}

	/** Removes every line equal to the given (trimmed) line. */
	private static void removeLine( File ini, String line ) {
		if( !ini.isFile() )
			return;
		try {
			List<String> keep = new ArrayList<String>();
			BufferedReader br = new BufferedReader( new FileReader( ini ));
			String l;
			while(( l = br.readLine() ) != null ) {
				if( !l.trim().equals( line ))
					keep.add( l );
			}
			br.close();
			PrintWriter pw = new PrintWriter( new FileWriter( ini, false ));
			for( String k : keep )
				pw.println( k );
			pw.close();
		} catch( Throwable t ) {
			Log.w( TAG, "removeLine failed", t );
		}
	}
}
