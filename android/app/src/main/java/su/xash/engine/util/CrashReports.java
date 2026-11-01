package su.xash.engine.util;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Build 114 merge: minimal Java stand-in for the engine app's CrashReports.kt.
 *
 * The Kotlin original pulls in appcompat + FileProvider + the share dialog;
 * the merged CS16Fresh app only needs the three pieces XashActivity calls
 * (STACKTRACE_NAME, writeSystemInfo, writeIntentInfo) plus the pending-dir
 * layout, which the engine's own crash path reads back from
 * filesDir/crashes/*. File layout and constant values are kept byte-identical
 * to CrashReports.kt so a crash captured here is still readable by the
 * engine's crash tooling.
 */
public final class CrashReports {
	public static final String STACKTRACE_NAME = "crash.log";
	public static final String SYSINFO_NAME = "sysinfo.txt";
	public static final String INTENT_NAME = "intent.txt";
	public static final String ENGINELOG_NAME = "engine.log";

	private CrashReports() {}

	public static File pendingDir( Context ctx ) {
		return new File( ctx.getFilesDir(), "crashes" );
	}

	public static File pendingStacktrace( Context ctx ) {
		return new File( pendingDir( ctx ), STACKTRACE_NAME );
	}

	public static File pendingSysinfo( Context ctx ) {
		return new File( pendingDir( ctx ), SYSINFO_NAME );
	}

	public static File pendingIntent( Context ctx ) {
		return new File( pendingDir( ctx ), INTENT_NAME );
	}

	public static File pendingEngineLog( Context ctx ) {
		return new File( pendingDir( ctx ), ENGINELOG_NAME );
	}

	/** same fields as CrashReports.kt's writeSystemInfo, minus BuildConfig */
	public static void writeSystemInfo( Context ctx ) {
		StringBuilder sb = new StringBuilder();
		sb.append( "Application ID: " ).append( ctx.getPackageName() ).append( '\n' );
		sb.append( "Android: " ).append( Build.VERSION.RELEASE )
			.append( " (SDK " ).append( Build.VERSION.SDK_INT ).append( ")\n" );
		sb.append( "Manufacturer: " ).append( Build.MANUFACTURER ).append( '\n' );
		sb.append( "Brand: " ).append( Build.BRAND ).append( '\n' );
		sb.append( "Model: " ).append( Build.MODEL ).append( '\n' );
		sb.append( "Device: " ).append( Build.DEVICE ).append( '\n' );
		sb.append( "Product: " ).append( Build.PRODUCT ).append( '\n' );
		sb.append( "Hardware: " ).append( Build.HARDWARE ).append( '\n' );
		sb.append( "Fingerprint: " ).append( Build.FINGERPRINT ).append( '\n' );
		sb.append( "Supported ABIs: " ).append( join( Build.SUPPORTED_ABIS ) ).append( '\n' );
		write( pendingSysinfo( ctx ), sb.toString() );
	}

	public static void writeIntentInfo( Context ctx, String text ) {
		write( pendingIntent( ctx ), text );
	}

	private static String join( String[] parts ) {
		StringBuilder sb = new StringBuilder();
		for( int i = 0; i < parts.length; i++ ) {
			if( i > 0 ) sb.append( ", " );
			sb.append( parts[i] );
		}
		return sb.toString();
	}

	private static void write( File f, String text ) {
		try {
			File dir = f.getParentFile();
			if( dir != null ) dir.mkdirs();
			FileOutputStream out = new FileOutputStream( f );
			try {
				out.write( text.getBytes( "UTF-8" ) );
			} finally {
				out.close();
			}
		} catch( Throwable ignored ) {
			// crash reporting must never take the app down
		}
	}
}
