package su.xash.cs16client;

import android.app.Activity;
import android.app.Dialog;
import android.app.DownloadManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Forced update gate for the launcher.
 *
 * On every start the launcher asks GitHub for the latest published release
 * and compares its build number (the NN in the 1.35.NN-<hash> tag, which is
 * identical to this app's versionName) with the one compiled in. If a newer
 * build exists a blocking dark card shows the release notes with sections,
 * the two versions side by side, live download progress (size counter) and
 * an install/retry flow - update or exit, there is no "continue with the
 * old version". Builds without a build number in their versionName
 * (pre-1.35.NN installs) count as build 0 so they are asked to update too.
 *
 * If the check cannot run (no network, GitHub unreachable, API rate limit)
 * the launcher starts normally - only a CONFIRMED newer release blocks.
 */
final class UpdateChecker {

    private static final String TAG = "UpdateChecker";

    // release source; the newest published tag (prereleases included - our
    // development builds are flagged as such and /releases/latest would
    // never see them) defines the newest build. A whole page is fetched and
    // the HIGHEST build number wins: a re-published old tag sorts to the
    // front of the list and used to mask newer builds (the "stuck on an old
    // build" report).
    private static final String API_URL =
            "https://api.github.com/repos/venomxtreme-cs/cs16fresh/releases?per_page=10";
    private static final String APK_NAME = "cs16client-app-git-release.apk";
    // downloaded into the app-specific external dir (no storage permission
    // needed to read it back, unlike the public Downloads dir)
    private static final String APK_FILE = "cs16client-update.apk";

    private static final AtomicLong sDownloadId = new AtomicLong( -1 );
    private static boolean sReceiverRegistered = false;
    private static Runnable sDownloadCompleteListener = null;

    // process-lifetime receiver for the download; delegates to whatever
    // dialog is currently on screen (null after it is dismissed)
    private static final BroadcastReceiver sDownloadCompleteReceiver =
            new BroadcastReceiver() {
        @Override
        public void onReceive( Context context, Intent intent ) {
            long id = intent.getLongExtra( DownloadManager.EXTRA_DOWNLOAD_ID, -1 );
            if( id != sDownloadId.get() )
                return;
            Runnable done = sDownloadCompleteListener;
            if( done != null )
                done.run();
        }
    };

    // dialog states
    private static final int STATE_IDLE = 0;
    private static final int STATE_DOWNLOADING = 1;
    private static final int STATE_INSTALLING = 2;

    private UpdateChecker() {}

    static void check(final Activity activity) {
        if( BuildConfig.IS_GOOGLE_PLAY_BUILD )
            return; // the store build updates through the store

        final int localBuild = buildNumberFrom( BuildConfig.VERSION_NAME );

        new Thread( new Runnable() {
            @Override
            public void run() {
                try {
                    // one retry: a flaky first request (slow TLS handshake,
                    // api hiccup) must not silently skip the update gate
                    Release release = null;
                    for( int attempt = 0; attempt < 2 && release == null; attempt++ ) {
                        if( attempt > 0 )
                            Thread.sleep( 1500 );
                        release = fetchLatestRelease();
                    }
                    if( release == null )
                        return;

                    int remoteBuild = buildNumberFrom( release.tag );
                    if( remoteBuild <= localBuild )
                        return; // up to date

                    final Release rel = release;
                    new Handler( Looper.getMainLooper() ).post( new Runnable() {
                        @Override
                        public void run() {
                            if( activity.isFinishing() || activity.isDestroyed() )
                                return;
                            showDialog( activity, rel );
                        }
                    } );
                } catch( Throwable t ) {
                    // offline / api unreachable -> never block the launcher
                    Log.i( TAG, "update check skipped: " + t );
                }
            }
        }, "update-check" ).start();
    }

    // ------------------------------------------------------------------

    /** the one release we need: tag, display name, notes, publish date */
    private static final class Release {
        final String tag, name, body, publishedAt;
        Release( String tag, String name, String body, String publishedAt ) {
            this.tag = tag;
            this.name = name;
            this.body = body;
            this.publishedAt = publishedAt;
        }
    }

    private static Release fetchLatestRelease() throws Exception {
        URL url = new URL( API_URL );
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            // generous timeouts: a slow api.github.com response used to trip
            // the old 6s budget and the gate silently gave up
            conn.setConnectTimeout( 10000 );
            conn.setReadTimeout( 15000 );
            conn.setRequestProperty( "User-Agent", "cs16client-launcher" );
            conn.setRequestProperty( "Accept", "application/vnd.github+json" );

            if( conn.getResponseCode() != 200 )
                return null;

            InputStream in = conn.getInputStream();
            StringBuilder sb = new StringBuilder();
            BufferedReader reader = new BufferedReader( new InputStreamReader( in ) );
            String line;
            while(( line = reader.readLine() ) != null )
                sb.append( line );
            reader.close();

            // the endpoint returns an array sorted by creation date, but a
            // re-published old tag can jump to the front - scan the whole
            // page and take the highest build number instead of [0]
            JSONArray arr = new JSONArray( sb.toString() );
            JSONObject best = null;
            int bestBuild = -1;
            for( int i = 0; i < arr.length(); i++ ) {
                JSONObject rel = arr.optJSONObject( i );
                if( rel == null )
                    continue;
                String t = rel.optString( "tag_name", "" );
                if( t.isEmpty() )
                    continue;
                int b = buildNumberFrom( t );
                if( b > bestBuild ) {
                    bestBuild = b;
                    best = rel;
                }
            }
            if( best == null )
                return null;

            return new Release( best.optString( "tag_name", "" ),
                    best.optString( "name", "" ),
                    best.optString( "body", "" ),
                    best.optString( "published_at", "" ) );
        } finally {
            conn.disconnect();
        }
    }

    /** NN of a "1.35.NN-<hash>" tag/version, or 0 when there is none. */
    private static int buildNumberFrom( String name ) {
        if( name == null )
            return 0;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile( "1\\.35\\.(\\d+)" ).matcher( name );
        if( m.find() ) {
            try {
                return Integer.parseInt( m.group( 1 ) );
            } catch( NumberFormatException ignored) { }
        }
        return 0;
    }

    // ------------------------------------------------------------------
    // blocking dark card: update or exit, nothing else
    // ------------------------------------------------------------------

    private static void showDialog( final Activity activity, final Release release ) {

        final int remoteBuild = buildNumberFrom( release.tag );

        final Dialog dialog = new Dialog( activity );
        dialog.setCancelable( false );
        dialog.setCanceledOnTouchOutside( false );
        dialog.setOnKeyListener( new android.content.DialogInterface.OnKeyListener() {
            @Override
            public boolean onKey( android.content.DialogInterface d, int keyCode,
                    KeyEvent event ) {
                return keyCode == KeyEvent.KEYCODE_BACK; // swallow back
            }
        } );

        // no title bar - the card carries its own header
        try {
            dialog.requestWindowFeature( android.view.Window.FEATURE_NO_TITLE );
        } catch( Throwable ignored ) { }

        // centered card on a dimmed background, launcher palette
        dialog.getWindow().setBackgroundDrawable( new ColorDrawable( 0x00000000 ) );
        dialog.setContentView( R.layout.dialog_update );

        final TextView from = (TextView) dialog.findViewById( R.id.upd_from );
        final TextView to = (TextView) dialog.findViewById( R.id.upd_to );
        final TextView releaseTitle = (TextView) dialog.findViewById( R.id.upd_release );
        final TextView date = (TextView) dialog.findViewById( R.id.upd_date );
        final TextView changelog = (TextView) dialog.findViewById( R.id.upd_changelog );
        final ProgressBar bar = (ProgressBar) dialog.findViewById( R.id.upd_progress );
        final TextView status = (TextView) dialog.findViewById( R.id.upd_status );
        final TextView primary = (TextView) dialog.findViewById( R.id.upd_primary );
        final TextView secondary = (TextView) dialog.findViewById( R.id.upd_secondary );

        from.setText( BuildConfig.VERSION_NAME );
        to.setText( release.tag );
        releaseTitle.setText( release.name.isEmpty()
                ? "development build " + remoteBuild : release.name );
        date.setText( formatDate( release.publishedAt ));
        changelog.setText( renderChangelog( release.body ));

        bar.setMax( 100 );

        final Handler handler = new Handler( Looper.getMainLooper() );
        final int[] state = { STATE_IDLE };

        final Runnable poller = new Runnable() {
            @Override
            public void run() {
                if( state[0] != STATE_DOWNLOADING )
                    return;
                long id = sDownloadId.get();
                if( id < 0 )
                    return;
                DownloadManager dm = (DownloadManager)
                        activity.getSystemService( Context.DOWNLOAD_SERVICE );
                if( dm == null )
                    return;
                Cursor c = dm.query( new DownloadManager.Query().setFilterById( id ));
                if( c != null ) {
                    try {
                        if( c.moveToFirst() ) {
                            long total = c.getLong( c.getColumnIndexOrThrow(
                                    DownloadManager.COLUMN_TOTAL_SIZE_BYTES ));
                            long done = c.getLong( c.getColumnIndexOrThrow(
                                    DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR ));
                            int pct = ( total > 0 ) ? (int)( done * 100 / total ) : 0;
                            bar.setProgress( pct );
                            if( total > 0 ) {
                                status.setText( "Downloading update  " + pct + "%  ("
                                        + mb( done ) + " / " + mb( total ) + " MB)" );
                            } else {
                                status.setText( "Downloading update  " + mb( done ) + " MB" );
                            }
                        }
                    } finally {
                        c.close();
                    }
                }
                handler.postDelayed( this, 400 );
            }
        };

        sDownloadCompleteListener = new Runnable() {
            @Override
            public void run() {
                handler.removeCallbacks( poller );

                state[0] = STATE_INSTALLING;
                primary.setEnabled( false );
                primary.setText( "INSTALLING" );
                status.setTextColor( 0xFF7E8CA0 );
                status.setText( "Handing the update to the system installer..." );

                // Verify the download and stream it into the installer session
                // OFF the main thread. This receiver runs on the main thread and
                // copying a ~50 MB apk there trips the ANR watchdog - Android 16
                // kills the app, which is the "update crashes" report.
                new Thread( new Runnable() {
                    @Override
                    public void run() {
                        final File apk = verifyDownload( activity,
                                sDownloadId.get() );
                        if( apk == null ) {
                            activity.runOnUiThread( new Runnable() {
                                @Override
                                public void run() {
                                    if( activity.isFinishing() || activity.isDestroyed() )
                                        return;
                                    fail( "Download failed - the file is missing. Tap update to retry.",
                                            activity, state, primary, bar, status );
                                }
                            } );
                            return;
                        }
                        installApk( activity, apk );
                    }
                }, "update-install" ).start();
            }
        };

        InstallStatusReceiver.sListener = new InstallStatusReceiver.Listener() {
            @Override
            public void onInstallStatus( int statusCode, String message ) {
                if( statusCode == PackageInstaller.STATUS_PENDING_USER_ACTION ) {
                    status.setTextColor( 0xFF7E8CA0 );
                    status.setText( "Confirm the install in the system dialog..." );
                    return;
                }
                if( statusCode == PackageInstaller.STATUS_SUCCESS ) {
                    // InstallStatusReceiver toasts + restarts into the new build
                    return;
                }
                Log.w( TAG, "install failed: status=" + statusCode + " " + message );
                fail( "Installation failed. Tap update to download again.",
                        activity, state, primary, bar, status );
            }
        };

        primary.setOnClickListener( new android.view.View.OnClickListener() {
            @Override
            public void onClick( android.view.View v ) {
                if( state[0] == STATE_IDLE ) {
                    // one-time per device: let the user allow this launcher to
                    // start the installer BEFORE anything is downloaded, so the
                    // flow never dead-ends at the install step
                    if( Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager()
                            .canRequestPackageInstalls() ) {
                        status.setTextColor( 0xFFFFC25E );
                        status.setText( "Allow installing apps for this launcher, "
                                + "then tap update again." );
                        try {
                            activity.startActivity( new Intent(
                                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse( "package:" + activity.getPackageName() ) ) );
                        } catch( Throwable t ) {
                            Log.w( TAG, "cannot open install permission screen", t );
                        }
                        return;
                    }

                    state[0] = STATE_DOWNLOADING;
                    primary.setText( "CANCEL" );
                    bar.setProgress( 0 );
                    status.setTextColor( 0xFF7E8CA0 );
                    status.setText( "Starting download..." );
                    status.setVisibility( android.view.View.VISIBLE );
                    bar.setVisibility( android.view.View.VISIBLE );
                    handler.removeCallbacks( poller );
                    handler.postDelayed( poller, 400 );
                    registerInstallReceiver( activity );
                    startDownload( activity, release.tag );
                } else if( state[0] == STATE_DOWNLOADING ) {
                    // cancel: drop the partial download and return to idle
                    DownloadManager dm = (DownloadManager)
                            activity.getSystemService( Context.DOWNLOAD_SERVICE );
                    if( dm != null )
                        dm.remove( sDownloadId.get() );
                    handler.removeCallbacks( poller );
                    state[0] = STATE_IDLE;
                    primary.setText( "UPDATE" );
                    bar.setVisibility( android.view.View.GONE );
                    status.setVisibility( android.view.View.GONE );
                }
            }
        } );

        secondary.setOnClickListener( new android.view.View.OnClickListener() {
            @Override
            public void onClick( android.view.View v ) {
                if( state[0] == STATE_DOWNLOADING ) {
                    DownloadManager dm = (DownloadManager)
                            activity.getSystemService( Context.DOWNLOAD_SERVICE );
                    if( dm != null )
                        dm.remove( sDownloadId.get() );
                }
                activity.finishAffinity();
                if( Build.VERSION.SDK_INT >= 21 )
                    android.os.Process.killProcess( android.os.Process.myPid() );
            }
        } );

        dialog.setOnDismissListener( new android.content.DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss( android.content.DialogInterface d ) {
                handler.removeCallbacks( poller );
                InstallStatusReceiver.sListener = null;
                sDownloadCompleteListener = null;
            }
        } );

        primary.setText( "UPDATE" );
        secondary.setText( "EXIT" );

        dialog.show();

        // size the card: full width with breathing room around it
        try {
            FrameLayout window = (FrameLayout) dialog.getWindow().getDecorView()
                    .findViewById( android.R.id.content );
            if( window != null ) {
                int margin = dp( activity, 24 );
                ViewGroup.LayoutParams raw = window.getLayoutParams();
                if( raw instanceof ViewGroup.MarginLayoutParams ) {
                    ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) raw;
                    lp.leftMargin = margin;
                    lp.rightMargin = margin;
                    window.setLayoutParams( lp );
                }
            }
        } catch( Throwable t ) {
            Log.w( TAG, "cannot apply dialog margins", t );
        }
    }

    /** reset the card to a retryable idle state after a download/install error. */
    private static void fail( String message, Activity activity, int[] state,
            TextView primary, ProgressBar bar, TextView status ) {
        state[0] = STATE_IDLE;
        primary.setEnabled( true );
        primary.setText( "UPDATE" );
        bar.setVisibility( android.view.View.GONE );
        status.setTextColor( 0xFFFF6B61 );
        status.setText( message );
        status.setVisibility( android.view.View.VISIBLE );
    }

    /** "## header" lines become accent section titles, "- item" becomes a bullet. */
    private static CharSequence renderChangelog( String body ) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        if( body != null ) {
            for( String raw : body.split( "\n" ) ) {
                String line = raw.trim();
                if( line.isEmpty() )
                    continue;
                if( line.startsWith( "## " ) ) {
                    if( sb.length() > 0 )
                        sb.append( "\n" );
                    String header = line.substring( 3 ).trim()
                            .toUpperCase( Locale.ROOT );
                    int start = sb.length();
                    sb.append( header );
                    sb.setSpan( new ForegroundColorSpan( 0xFFFFC25E ),
                            start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE );
                    sb.setSpan( new StyleSpan( Typeface.BOLD ),
                            start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE );
                    sb.append( "\n" );
                } else if( line.startsWith( "- " ) || line.startsWith( "* " ) ) {
                    sb.append( "  \u2022  " ).append( line.substring( 2 ).trim() )
                            .append( "\n" );
                } else {
                    sb.append( line ).append( "\n" );
                }
            }
        }
        if( sb.length() == 0 )
            sb.append( "This update contains internal improvements and fixes." );
        return sb;
    }

    private static String formatDate( String iso ) {
        try {
            if( iso != null && iso.length() >= 10 ) {
                Date d = new SimpleDateFormat( "yyyy-MM-dd", Locale.ROOT )
                        .parse( iso.substring( 0, 10 ) );
                if( d != null )
                    return new SimpleDateFormat( "d MMM yyyy", Locale.ENGLISH )
                            .format( d );
            }
        } catch( Throwable ignored) { }
        return "";
    }

    private static String mb( long bytes ) {
        return String.format( Locale.ROOT, "%.1f", bytes / 1048576.0 );
    }

    // ------------------------------------------------------------------
    // download + hand the apk to the system installer
    // ------------------------------------------------------------------

    private static void startDownload( final Activity activity, String tag ) {
        String apkUrl = "https://github.com/venomxtreme-cs/cs16fresh/releases/download/"
                + tag + "/" + APK_NAME;

        DownloadManager dm = (DownloadManager)
                activity.getSystemService( Context.DOWNLOAD_SERVICE );
        if( dm == null )
            return;

        // drop any previous/partial download so a stale or failed file is
        // never handed to the installer (Android 16 can stop a background
        // download and the completion receiver still fires)
        long prev = sDownloadId.getAndSet( -1 );
        if( prev >= 0 ) {
            try { dm.remove( prev ); } catch( Throwable ignored ) {}
        }
        File stale = updateApkFile( activity );
        if( stale != null && stale.exists() )
            stale.delete();

        DownloadManager.Request req = new DownloadManager.Request( Uri.parse( apkUrl ) );
        req.setTitle( "Updating CS16Client" );
        req.setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED );
        // app-specific dir: readable with no storage permission, unlike the
        // public Downloads dir which needs MANAGE_EXTERNAL_STORAGE on 13+
        req.setDestinationInExternalFilesDir( activity, null, APK_FILE );
        req.setMimeType( "application/vnd.android.package-archive" );

        long id = dm.enqueue( req );
        sDownloadId.set( id );
    }

    /** the app-specific file the update apk is downloaded into (no permission needed). */
    private static File updateApkFile( Context ctx ) {
        File dir = ctx.getExternalFilesDir( null );
        if( dir == null )
            dir = new File( ctx.getFilesDir(), "updates" );
        return new File( dir, APK_FILE );
    }

    /**
     * Confirms the download actually succeeded and returns the apk, or null
     * when it failed / is missing (never install a stale or partial file).
     * Runs on a background thread.
     */
    private static File verifyDownload( Context ctx, long id ) {
        if( id < 0 )
            return null;
        DownloadManager dm = (DownloadManager)
                ctx.getSystemService( Context.DOWNLOAD_SERVICE );
        if( dm == null )
            return null;
        Cursor c = dm.query( new DownloadManager.Query().setFilterById( id ));
        if( c == null )
            return null;
        try {
            if( !c.moveToFirst() )
                return null;
            int status = c.getInt( c.getColumnIndexOrThrow(
                    DownloadManager.COLUMN_STATUS ));
            if( status != DownloadManager.STATUS_SUCCESSFUL ) {
                Log.w( TAG, "download not successful: status=" + status );
                return null;
            }
        } finally {
            c.close();
        }
        File apk = updateApkFile( ctx );
        if( !apk.isFile() || apk.length() == 0 )
            return null;
        return apk;
    }

    private static void registerInstallReceiver( final Activity activity ) {
        if( sReceiverRegistered )
            return;
        sReceiverRegistered = true;

        IntentFilter filter =
                new IntentFilter( DownloadManager.ACTION_DOWNLOAD_COMPLETE );
        // Android 13+ wants an explicit export flag; DOWNLOAD_COMPLETE is a
        // protected system broadcast so NOT_EXPORTED still receives it.
        if( Build.VERSION.SDK_INT >= 33 ) {
            activity.registerReceiver( sDownloadCompleteReceiver, filter,
                    Context.RECEIVER_NOT_EXPORTED );
        } else {
            activity.registerReceiver( sDownloadCompleteReceiver, filter );
        }
    }

    // ------------------------------------------------------------------
    // hand the downloaded apk to the system installer (framework-only,
    // no support-library FileProvider required)
    // ------------------------------------------------------------------

    /**
     * Hands the downloaded apk to the system installer. The caller must
     * already be on a background thread: the session copy streams the whole
     * apk and must never run on the main thread.
     */
    private static void installApk( final Activity activity, File apk ) {
        PackageManager pm = activity.getPackageManager();

        // one-time per device: allow this launcher to start the installer.
        // startActivity has to run on the main thread.
        if( Build.VERSION.SDK_INT >= 26 && !pm.canRequestPackageInstalls() ) {
            activity.runOnUiThread( new Runnable() {
                @Override
                public void run() {
                    try {
                        activity.startActivity( new Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse( "package:" + activity.getPackageName() ) ) );
                    } catch( Throwable t ) {
                        Log.w( TAG, "cannot open install permission screen", t );
                    }
                }
            } );
            return;
        }

        PackageInstaller installer = pm.getPackageInstaller();
        int sessionId;
        try {
            sessionId = installer.createSession( new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL ) );
        } catch( IOException e ) {
            Log.w( TAG, "cannot create install session", e );
            return;
        }

        boolean committed = false;
        PackageInstaller.Session session = null;
        try {
            session = installer.openSession( sessionId );

            OutputStream out = session.openWrite( "base.apk", 0, -1 );
            try {
                InputStream in = new FileInputStream( apk );
                try {
                    byte[] buf = new byte[65536];
                    int n;
                    while(( n = in.read( buf )) > 0 )
                        out.write( buf, 0, n );
                } finally {
                    in.close();
                }
                session.fsync( out );
            } finally {
                out.close();
            }

            Intent status = new Intent( activity, InstallStatusReceiver.class );
            PendingIntent pi = PendingIntent.getBroadcast( activity, sessionId, status,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE );
            session.commit( pi.getIntentSender() );
            committed = true;
        } catch( IOException e ) {
            Log.w( TAG, "writing update apk failed", e );
        } finally {
            if( session != null ) {
                try {
                    session.close();
                } catch( Throwable ignored ) { }
            }
            if( !committed ) {
                try {
                    installer.abandonSession( sessionId );
                } catch( Throwable ignored ) { }
            }
        }
    }

    // ------------------------------------------------------------------

    private static int dp( Activity activity, int value ) {
        float density = activity.getResources().getDisplayMetrics().density;
        return (int) ( value * density + 0.5f );
    }
}
