package su.xash.cs16client;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Environment;

import java.io.File;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The engine's crash / engine log (engine.log, written into
 * XASH3D_BASEDIR when -log is on): find the newest copy, describe it,
 * read its tail, share it, clear it. Used by the Settings tab and the
 * log viewer (build 113: "settings that in xash crash log and more").
 */
final class EngineLog {
	/** tail shown / shared - the interesting end of a big log */
	static final long MAX_READ = 512L * 1024L;

	private EngineLog() {
	}

	/** every place the engine may have left the log, newest first pick */
	static File[] candidates( Context ctx ) {
		File ext = Environment.getExternalStorageDirectory();
		File[] extFiles = null;
		try {
			extFiles = ctx.getExternalFilesDir( null );
		} catch( Throwable ignored ) {
		}
		return new File[] {
			new File( ext, "xash/engine.log" ),
			new File( ext, "xash/cstrike/engine.log" ),
			new File( ext, "xash/valve/engine.log" ),
			extFiles != null ? new File( extFiles, "engine.log" ) : null,
			new File( ctx.getFilesDir(), "engine.log" ),
		};
	}

	/** newest existing log file, or null when none was written yet */
	static File find( Context ctx ) {
		File best = null;
		for( File f : candidates( ctx )) {
			if( f == null || !f.isFile())
				continue;
			if( best == null || f.lastModified() > best.lastModified())
				best = f;
		}
		return best;
	}

	/** "path - size - modified" one-liner for the status line */
	static String describe( File f ) {
		long len = f.length();
		String size;
		if( len < 1024L )
			size = len + " B";
		else if( len < 1024L * 1024L )
			size = String.format( Locale.US, "%.1f KB", len / 1024.0 );
		else
			size = String.format( Locale.US, "%.1f MB", len / ( 1024.0 * 1024.0 ));

		String when = new SimpleDateFormat( "dd.MM.yyyy HH:mm", Locale.US )
			.format( new Date( f.lastModified()));
		return f.getAbsolutePath() + "  -  " + size + "  -  " + when;
	}

	/** last MAX_READ bytes as text; a cut marker when the head was dropped */
	static String readTail( File f ) {
		if( f == null )
			return "";
		try {
			RandomAccessFile raf = new RandomAccessFile( f, "r" );
			long len = raf.length();
			long start = Math.max( 0, len - MAX_READ );
			raf.seek( start );
			byte[] buf = new byte[(int)( len - start )];
			raf.readFully( buf );
			raf.close();

			String s = new String( buf, "UTF-8" );
			if( start > 0 ) {
				// drop the likely torn first line
				int nl = s.indexOf( '\n' );
				if( nl >= 0 && nl + 1 < s.length())
					s = s.substring( nl + 1 );
				s = "... (log tail - earlier lines cut) ...\n" + s;
			}
			return s;
		} catch( Throwable t ) {
			return "";
		}
	}

	/** ACTION_SEND chooser with the log tail; false when nothing to send */
	static boolean share( Context ctx ) {
		String text = readTail( find( ctx ));
		if( text.length() == 0 )
			return false;
		try {
			Intent i = new Intent( Intent.ACTION_SEND );
			i.setType( "text/plain" );
			i.putExtra( Intent.EXTRA_SUBJECT, "engine.log" );
			i.putExtra( Intent.EXTRA_TEXT, text );
			ctx.startActivity( Intent.createChooser( i, "Engine log" ));
			return true;
		} catch( Throwable t ) {
			return false;
		}
	}

	/** truncate every copy we can reach; false when there was none */
	static boolean clear( Context ctx ) {
		boolean any = false;
		for( File f : candidates( ctx )) {
			if( f == null || !f.isFile())
				continue;
			try {
				RandomAccessFile raf = new RandomAccessFile( f, "rw" );
				raf.setLength( 0 );
				raf.close();
				any = true;
			} catch( Throwable ignored ) {
			}
		}
		return any;
	}

	static void copyToClipboard( Context ctx, String text ) {
		try {
			ClipboardManager cm = (ClipboardManager)
				ctx.getSystemService( Context.CLIPBOARD_SERVICE );
			if( cm != null )
				cm.setPrimaryClip( ClipData.newPlainText( "engine.log", text ));
		} catch( Throwable ignored ) {
		}
	}
}
