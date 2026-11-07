package su.xash.cs16client;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads the public module catalog (github.com/venomxtreme-cs/cs16fresh-modules)
 * and installs a module's .so files into the game tree.
 *
 * Layout: &lt;Game&gt;/&lt;module&gt;/&lt;lib*.so&gt;, where &lt;Game&gt; is
 * "Counter-Strike" or "Half-Life". A file named libamxx_* is an AMXX module,
 * any other lib*.so is a Metamod plugin. A module ships one .so per ABI
 * (arm64 / armv7a); each is downloaded and installed through ModuleInstaller,
 * which detects the ABI from the file content and auto-registers it.
 *
 * The catalog is fetched live from GitHub, so a module published (or updated)
 * by the admin shows up here without an app update.
 */
public final class ModuleCatalog {
	private static final String TAG = "ModuleCatalog";

	private static final String REPO = "venomxtreme-cs/cs16fresh-modules";
	private static final String BRANCH = "main";
	private static final String TREE_URL =
		"https://api.github.com/repos/" + REPO + "/git/trees/" + BRANCH + "?recursive=1";
	private static final String RAW_URL =
		"https://raw.githubusercontent.com/" + REPO + "/" + BRANCH + "/";

	/**
	 * Modules the app already bundles, so offering one here would install a
	 * duplicate that fights the shipped copy. Skipped at fetch time.
	 */
	private static final Set<String> BUNDLED = new HashSet<String>();
	static {
		BUNDLED.add( "reapi" );		// shipped inside amxmodx itself
	}

	private ModuleCatalog() {
	}

	/** One publishable module in the catalog. */
	public static class Module {
		public final String game;	// ModuleInstaller.GAME_CSTRIKE / GAME_VALVE
		public final String name;	// base name, e.g. "weaponmod"
		public final int kind;		// ModuleInstaller.KIND_AMXX / KIND_METAMOD
		public final List<String> soPaths = new ArrayList<String>(); // repo paths

		/**
		 * Digest of the module's .so blobs exactly as published (sha1 over the
		 * sorted "path:blobsha" lines). Compared against the value recorded at
		 * install time so UPDATE only appears when the admin pushed a new
		 * version. Empty when it could not be computed.
		 */
		public String sha = "";

		// "path:blobsha" pairs collected while fetching, digested into sha.
		private final List<String> blobPairs = new ArrayList<String>();

		Module( String game, String name, int kind ) {
			this.game = game;
			this.name = name;
			this.kind = kind;
		}
	}

	/** true when a module with this base name + kind is already installed for game. */
	public static boolean isInstalled( String game, int kind, String name ) {
		for( ModuleInstaller.Entry e : ModuleInstaller.list( game )) {
			if( e.kind == kind && e.base.equalsIgnoreCase( name ) && e.present )
				return true;
		}
		return false;
	}

	// ------------------------------------------------------------------
	// fetch
	// ------------------------------------------------------------------

	/** Fetches the catalog: one Module per &lt;Game&gt;/&lt;module&gt; dir holding .so files. */
	public static List<Module> fetch() throws Exception {
		String json = httpGet( TREE_URL );
		JSONObject root = new JSONObject( json );
		JSONArray tree = root.optJSONArray( "tree" );
		if( tree == null )
			return new ArrayList<Module>();

		// group .so files by "<Game>/<module>"
		Map<String, Module> byKey = new LinkedHashMap<String, Module>();
		for( int i = 0; i < tree.length(); i++ ) {
			JSONObject item = tree.optJSONObject( i );
			if( item == null || !"blob".equals( item.optString( "type" )))
				continue;
			String path = item.optString( "path", "" );
			if( !path.toLowerCase( Locale.US ).endsWith( ".so" ))
				continue;
			String[] parts = path.split( "/" );
			if( parts.length < 3 )
				continue;			// <Game>/<module>/<file>.so
			String game = gameFor( parts[0] );
			String module = parts[1];
			if( game == null || module.isEmpty() )
				continue;
			if( BUNDLED.contains( module.toLowerCase( Locale.US )))
				continue;	// bundled in the app already - never offer a duplicate

			// A7 build 142 (round 142 item 4, user: "remove modules
			// weaponmod that in half life from github we made new post of
			// custom modules , so remove it"): the Half-Life weaponmod is
			// retired from the published catalog - never offered for the
			// valve target. Any other game keeps every published module.
			if( ModuleInstaller.GAME_VALVE.equals( game )
					&& "weaponmod".equalsIgnoreCase( module )) {
				// marker scanned from the dex by scan112
				Log.i( TAG, "build142: weaponmod (Half-Life) retired from catalog" );
				continue;
			}

			String key = game + "/" + module;
			Module m = byKey.get( key );
			if( m == null ) {
				m = new Module( game, module, kindFor( parts[2] ));
				byKey.put( key, m );
			}
			m.soPaths.add( path );
			m.blobPairs.add( path + ":" + item.optString( "sha", "" ));
		}
		for( Module m : byKey.values())
			m.sha = digest( m.blobPairs );
		return new ArrayList<Module>( byKey.values() );
	}

	/**
	 * Stable digest of a module's published .so blobs: sha1 over the sorted
	 * "path:blobsha" lines. Sorting makes it independent of tree order; the git
	 * blob sha is content-addressed, so any upload of a new .so changes it.
	 */
	private static String digest( List<String> pairs ) {
		if( pairs.isEmpty())
			return "";
		List<String> sorted = new ArrayList<String>( pairs );
		Collections.sort( sorted );
		StringBuilder sb = new StringBuilder();
		for( String s : sorted )
			sb.append( s ).append( '\n' );
		try {
			MessageDigest md = MessageDigest.getInstance( "SHA-1" );
			byte[] d = md.digest( sb.toString().getBytes( "UTF-8" ));
			StringBuilder hex = new StringBuilder( d.length * 2 );
			for( int i = 0; i < d.length; i++ )
				hex.append( String.format( Locale.US, "%02x", d[i] ));
			return hex.toString();
		} catch( Exception e ) {
			Log.w( TAG, "sha1 digest failed", e );
			return "";
		}
	}

	private static String gameFor( String dir ) {
		String d = dir.toLowerCase( Locale.US );
		if( d.equals( "counter-strike" ) || d.equals( "cstrike" ) || d.equals( "cs" ))
			return ModuleInstaller.GAME_CSTRIKE;
		if( d.equals( "half-life" ) || d.equals( "valve" ) || d.equals( "hl" ))
			return ModuleInstaller.GAME_VALVE;
		return null;
	}

	private static int kindFor( String file ) {
		// libamxx_<name>[_<abi>].so -> AMXX module; lib<name>[_<abi>].so -> Metamod
		return file.toLowerCase( Locale.US ).startsWith( "libamxx_" )
			? ModuleInstaller.KIND_AMXX : ModuleInstaller.KIND_METAMOD;
	}

	// ------------------------------------------------------------------
	// install (background thread)
	// ------------------------------------------------------------------

	/**
	 * Downloads every .so in the module and installs it (one per ABI).
	 * Returns a summary result; individual ABI failures are folded in.
	 */
	public static ModuleInstaller.Result install( Context ctx, Module m ) {
		int ok = 0, fail = 0;
		ModuleInstaller.Result last = null;
		for( String path : m.soPaths ) {
			File tmp = null;
			try {
				tmp = File.createTempFile( "catmod", ".so", ctx.getCacheDir() );
				if( !downloadTo( RAW_URL + path, tmp ))
					throw new Exception( "download failed" );
				last = ModuleInstaller.install( m.kind, m.game, tmp,
					fileNameOf( path ));
				if( last.ok )
					ok++;
				else
					fail++;
			} catch( Throwable t ) {
				Log.w( TAG, "catalog install failed for " + path, t );
				fail++;
				last = new ModuleInstaller.Result( false, m.name + ": " + t.getMessage());
			} finally {
				if( tmp != null && tmp.exists() )
					tmp.delete();
			}
		}
		if( fail == 0 ) {
			// Only a clean install pins the digest: a half-installed module
			// must keep showing UPDATE until it finishes.
			recordSha( m.game, m.name, m.sha );
			return new ModuleInstaller.Result( true,
				m.name + " installed (" + ok + " ABI)" );
		}
		if( ok == 0 )
			return last != null ? last
				: new ModuleInstaller.Result( false, m.name + " install failed" );
		return new ModuleInstaller.Result( true,
			m.name + ": " + ok + " ok, " + fail + " failed" );
	}

	// ------------------------------------------------------------------
	// installed digest (drives the UPDATE button)
	// ------------------------------------------------------------------

	private static File shaFile( String game ) {
		return new File( new File( ModuleInstaller.xashDir(), game ),
			"addons/.catalog-sha" );
	}

	/**
	 * The digest recorded when this catalog module was last installed here, or
	 * null when it was never installed through the catalog (installed before
	 * this feature, placed by hand, or removed from the record).
	 */
	public static String installedSha( String game, String name ) {
		File f = shaFile( game );
		if( !f.isFile())
			return null;
		try {
			BufferedReader br = new BufferedReader( new FileReader( f ));
			String line;
			while(( line = br.readLine()) != null ) {
				line = line.trim();
				if( line.isEmpty() || line.startsWith( "#" ))
					continue;
				String[] p = line.split( "\\s+", 2 );
				if( p.length == 2 && p[0].equalsIgnoreCase( name )) {
					String sha = p[1].trim();
					br.close();
					return sha.isEmpty() ? null : sha;
				}
			}
			br.close();
		} catch( Throwable ignored ) {
		}
		return null;
	}

	/**
	 * Pins the digest of what was just installed so UPDATE can be shown only
	 * when the published content actually changes. One "&lt;name&gt; &lt;sha&gt;"
	 * line per module; an existing line for the same module is replaced.
	 */
	private static void recordSha( String game, String name, String sha ) {
		if( sha == null || sha.isEmpty())
			return;
		File f = shaFile( game );
		try {
			File parent = f.getParentFile();
			if( parent != null )
				parent.mkdirs();
			List<String> keep = new ArrayList<String>();
			if( f.isFile()) {
				BufferedReader br = new BufferedReader( new FileReader( f ));
				String line;
				while(( line = br.readLine()) != null ) {
					String t = line.trim();
					if( !t.isEmpty() && !t.startsWith( "#" )) {
						String[] p = t.split( "\\s+", 2 );
						if( p.length == 2 && p[0].equalsIgnoreCase( name ))
							continue;	// replaced below
					}
					keep.add( line );
				}
				br.close();
			}
			PrintWriter pw = new PrintWriter( new FileWriter( f, false ));
			for( String k : keep )
				pw.println( k );
			pw.println( name + " " + sha );
			pw.close();
		} catch( Throwable t ) {
			Log.w( TAG, "catalog sha write failed", t );
		}
	}

	// ------------------------------------------------------------------
	// http + file helpers
	// ------------------------------------------------------------------

	private static String httpGet( String urlStr ) throws Exception {
		URL url = new URL( urlStr );
		HttpURLConnection conn = (HttpURLConnection) url.openConnection();
		try {
			conn.setConnectTimeout( 8000 );
			conn.setReadTimeout( 8000 );
			conn.setRequestProperty( "User-Agent", "cs16client-modules" );
			conn.setRequestProperty( "Accept", "application/vnd.github+json" );
			if( conn.getResponseCode() != 200 )
				throw new Exception( "HTTP " + conn.getResponseCode() );
			BufferedReader r = new BufferedReader(
				new InputStreamReader( conn.getInputStream() ));
			StringBuilder sb = new StringBuilder();
			String line;
			while(( line = r.readLine() ) != null )
				sb.append( line );
			r.close();
			return sb.toString();
		} finally {
			conn.disconnect();
		}
	}

	private static boolean downloadTo( String urlStr, File dest ) {
		HttpURLConnection conn = null;
		try {
			conn = (HttpURLConnection) new URL( urlStr ).openConnection();
			conn.setConnectTimeout( 8000 );
			conn.setReadTimeout( 20000 );
			conn.setRequestProperty( "User-Agent", "cs16client-modules" );
			if( conn.getResponseCode() != 200 )
				return false;
			InputStream in = conn.getInputStream();
			FileOutputStream out = new FileOutputStream( dest );
			try {
				byte[] buf = new byte[65536];
				int n;
				while(( n = in.read( buf )) > 0 )
					out.write( buf, 0, n );
			} finally {
				out.close();
				in.close();
			}
			return dest.length() > 0;
		} catch( Throwable t ) {
			Log.w( TAG, "download failed: " + urlStr, t );
			return false;
		} finally {
			if( conn != null )
				conn.disconnect();
		}
	}

	private static String fileNameOf( String path ) {
		int slash = path.lastIndexOf( '/' );
		return slash >= 0 ? path.substring( slash + 1 ) : path;
	}
}
