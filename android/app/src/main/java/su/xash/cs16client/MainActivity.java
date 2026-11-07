package su.xash.cs16client;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.PrintWriter;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MainActivity extends Activity {
        private static final String TAG = "CS16Client";

        // Version stamp for assets/addons.zip. Tied to the app version so
        // every new build re-extracts the bundle - a manual bump was easy
        // to forget and updated .so files silently stayed on disk after an
        // app update ("the update didn't replace all of them").
        // (modules.ini is always refreshed so the sqlite/"sqlx" class
        //  that plugins like amx_gag need is enabled; amxx configs are
        //  kept - see extractZipIfNeeded)
        // amxx-1.9.0.5304: metamod rebuilt with RTLD_GLOBAL game-DLL loading
        // (ReAPI entity-class dynamic_cast fix for get_member_s plugins)
        // amxx-1.9.0.5309: plugins.ini read from the public gamedir when the
        // private module-dir copy misses (only *.so files are copied there;
        // AMX Mod X attaches again), debug spam removed from engine.log
        // amxx-1.9.0.5310: startup pass skips already-loaded child plugins
        // and regcvar memloc misses are gated debug -- clean boot log
        // amxx-1.9.0.5311: ArrayGetStringHandle/%a go through a validated
        // handle-table token -- 32-bit cells no longer truncate 64-bit
        // pointers (crash inside format()/AddString)
        // amxx-1.9.0.5313: the sqlite module guards the lazily-created queue
        // lock in ShutdownThreading() -- quitting from the menu before any
        // map loads detached it with a null mutex (SIGSEGV on shutdown)
        // amxx-1.9.0.5314: fakemeta/hamsandwich pdata arm64 table gains the
        // m_signals sub-fields (m_flSignal/m_flState), fixing the "untranslated
        // arm64 cell key=240" plugin offsets seen with heavy mods
        // "-a7bg": force ONE bundle re-extract so the CS 1.6 menu splash
        // (cstrike/gfx/shell/splash.bmp) replaces the Half-Life splash that
        // old game-data packages left in the gamedir - see mayOverwrite
        //
        // A7 build 136 (item 2, user: "when i click launcher game it take
        // time, fix it make work fast"): this stamp used to embed
        // BuildConfig.VERSION_NAME, so EVERY APK build (even with an
        // identical assets/addons.zip) failed the gate and re-extracted the
        // whole ~100 MB bundle on first launch - the biggest remaining wait
        // on the tap path. The gate is now the CRC32 of the asset itself
        // (assetGateVersion()): ordinary builds hit the warm path (marker
        // file + unchanged stamp == instant), only a real bundle change
        // re-extracts. One-time re-extract here because the stamp format
        // changed.

        // separate gate for the bot asset sync (yaPB conf/graphs, eBot
        // configs/waypoints, ReGameDLL BotProfile/BotChatter dbs) - bump
        // this alone when those change so the addons.zip bundle stays
        // untouched.
        // r3: the sync ships in the APK again (the native step used to
        //  delete the tree from the assets), the presence check covers
        //  avatars.cfg + BotProfile.db, and those two databases are
        //  installed to the gamedir root for the first time
        private static final String BOT_ASSETS_VERSION = "ebot-wp-r3";

        private boolean mPermissionAsked = false;

        // A7 build 127 (round 128): guards the background addons install.
        // onCreate(), onResume() and onRequestPermissionsResult() all funnel
        // into proceed(), so without this gate the second entry point would
        // start a second zip walk + sha256 pass over the first one.
        private static final java.util.concurrent.atomic.AtomicBoolean mInstallRunning =
                new java.util.concurrent.atomic.AtomicBoolean( false );

        // A7 build 128 (round 129, user: "fix launcher it too late to start
        // game"): the PLAY tap used to run its whole prep chain ON THE UI
        // THREAD before startActivity() - addon marker checks, the
        // plugins.ini/modules.ini rewrites and the depth-5 pak search that
        // walks the xash tree twice (one listFiles() per directory through
        // Android FUSE) - so taps sat frozen. The same chain now runs ONCE,
        // off the UI thread, on the install thread above ("warm"), and the
        // tap only reads the results from here.
        private volatile boolean mLaunchPrepDone = false;
        private final java.util.HashMap<String, String> mPakDirCache =
                new java.util.HashMap<String, String>();

        // A7 build 141 (round 141 item 2, user: "fix when click start game
        // still being too late"): true once this launch's bundle pass has
        // FINISHED writing to disk (gate hit or full extract). While it is
        // false a PLAY tap must queue - the bundle may be half-written.
        // While it is true the tap starts IMMEDIATELY even though the
        // install thread is still running: before this build the queue
        // covered the whole pass (extract + stamp rewrites + 3 FUSE pak
        // walks + up to 6 dlopen of ~30 MB), which is exactly the first
        // second after opening the launcher - the moment he taps.
        private volatile boolean mBundleCurrent = false;

        // A tap that lands while the bundle install is still running (the
        // first launch after every app update re-extracts it) is remembered
        // here and fired the moment the install thread releases its gate -
        // a pressed PLAY never dies quietly.
        private volatile String mPendingLaunchGame = null;
        private volatile long mPendingLaunchTapMs = -1;

        @Override
        protected void onCreate( Bundle savedInstanceState ) {
                super.onCreate( savedInstanceState );

                mPermissionAsked = savedInstanceState != null
                        && savedInstanceState.getBoolean( "permission_asked", false );

                // ask at most once per install - remembering the
                // flag across launches stops the per-launch dialog nagging
                if( !mPermissionAsked ) {
                        mPermissionAsked = getPreferences( MODE_PRIVATE )
                                .getBoolean( "permission_asked", false );
                }

                // blocking update gate: when GitHub lists a newer build the
                // launcher offers the update and refuses to continue until
                // it is installed (no-op when offline or up to date)
                UpdateChecker.check( this );

                // Always render the launcher UI first so the user is never
                // left on a blank/stuck screen. If storage access is missing,
                // the menu shows a tappable "storage access required" banner
                // and (on the very first ask) we open the system settings page
                // once so they can grant it. onResume() finishes the install
                // once the permission is granted.
                if( !hasStorageAccess() ) {
                        showMenu();

                        if( !mPermissionAsked ) {
                                mPermissionAsked = true;
                                requestStorageAccess();
                        }
                        return;
                }

                proceed();
        }

        @Override
        protected void onSaveInstanceState( Bundle outState ) {
                super.onSaveInstanceState( outState );
                outState.putBoolean( "permission_asked", mPermissionAsked );
        }

        @Override
        protected void onResume() {
                super.onResume();

                // finish the install once the user grants storage access in
                // system settings; otherwise just keep the banner in sync
                // (the persistent banner is the call-to-action, no toast spam)
                if( hasStorageAccess() ) {
                        mPermissionAsked = false;
                        proceed();
                } else {
                        updateStorageBanner();
                }
        }

        private void proceed() {
                syncSwitchPrefsWithMarkers();

                // A7 build 127 (round 128): showMenu() FIRST. The install used
                // to run here on the UI thread BEFORE the menu was inflated -
                // a full zip walk + a sha256 per file over Android FUSE that
                // re-runs on every version bump - so the user stared at a
                // blank/frozen launcher on start ("fix launch ... too late like
                // freeze"). The menu is revealed immediately now, the heavy
                // work happens off the UI thread below.
                showMenu();

                // onCreate() + onResume() (and onRequestPermissionsResult())
                // all land here: only the first caller may start the install.
                // The play-button gate goes AFTER the guard: a late second
                // caller must not disable the button and then return without
                // anyone left to re-enable it.
                if( !mInstallRunning.compareAndSet( false, true ) )
                        return;

                // A7 build 128 (round 129): the PLAY button is NO LONGER disabled while
                // the install runs. A disabled button just sits there ignoring
                // taps ("launcher too late to start game" - the first launch
                // after an update re-extracts the bundle, so the PLAY button
                // was dead for that whole time). The button is pressable now and the
                // tap is QUEUED by beginLaunch() instead - it fires the moment
                // this install finishes.

                new Thread( new Runnable() {
                        @Override
                        public void run() {
                                final long startMs = android.os.SystemClock.elapsedRealtime();

                                // true while THIS pass still owes a good bundle - a caught
                                // extraction error below must keep the tap
                                // queue in place (half-written bundle)
                                boolean extractFailed = false;

                                try {
                                        try {
                                                extractAddonsIfNeeded();
                                        } catch( Throwable t ) {
                                                extractFailed = true;
                                                Log.w( TAG, "addons extraction failed", t );
                                                logInstallError( "cstrike addons (cs16client)", t );
                                                // Toast from a non-UI thread throws
                                                final String msg = "Addons installation error: " + t.getMessage();
                                                runOnUiThread( new Runnable() {
                                                        @Override
                                                        public void run() {
                                                                Toast.makeText( MainActivity.this,
                                                                        msg, Toast.LENGTH_LONG ).show();
                                                        }
                                                } );
                                        }

                                        // the fvault seeds ride with no zip; they appear only
                                        // while the Zombie Plague X pack is installed and enabled
                                        ensureZpxVaultSeeds();

                                        // A7 build 141 (round 141 item 2, "fix
                                        // when click start game still being too
                                        // late"): the bundle + seeds are fully on
                                        // disk now. From THIS point a PLAY tap
                                        // starts the engine even while the warm
                                        // work below (3 FUSE pak walks + up to
                                        // 6 dlopen of ~30 MB) is still running.
                                        // Before this flag the tap queued for
                                        // the WHOLE pass, and that window IS the
                                        // first second after opening the
                                        // launcher - exactly when he taps.
                                        // extractFailed keeps it false:
                                        // a half-written bundle must still queue.
                                        if( extractFailed ) {
                                                Log.w( TAG, "[A7] build141: bundle extract FAILED - taps stay queued" );
                                        } else {
                                                mBundleCurrent = true;
                                                Log.i( TAG, "[A7] build141: bundle on disk - PLAY tap no longer waits ("
                                                        + ( android.os.SystemClock.elapsedRealtime() - startMs )
                                                        + " ms after launcher start)" );
                                        }
                                } finally {
                                        Log.i( TAG, "[A7] build127: background install took " + (android.os.SystemClock.elapsedRealtime() - startMs) + " ms" );

                                        // A7 build 128 (round 129): still on the
                                        // install thread, BEFORE the gate opens -
                                        // warm the launch path (the pak search over
                                        // the xash tree) so the first PLAY tap is a
                                        // cache hit instead of a filesystem walk.
                                        try {
                                                warmLaunchCache();
                                        } catch( Throwable t ) {
                                                Log.w( TAG, "launch warm failed", t );
                                        }

                                        // hand the gate back to the user and re-evaluate
                                        // the engine/addons status lines BEFORE releasing
                                        // mInstallRunning - otherwise a late proceed() sees
                                        // "idle" while this enable is still queued and the
                                        // button is re-disabled with no install left to
                                        // finish it
                                        setPlayButtonEnabled( true );
                                        mInstallRunning.set( false );

                                        // A tap arrived while the bundle was installing:
                                        // fire it now that the engine can start.
                                        firePendingLaunch();
                                }
                        }
                }, "addons-install" ).start();
        }

        // ------------------------------------------------------------------
        // A7 build 127 (round 128): play-button gate for the background
        // install. Everything runs inside runOnUiThread() so the install
        // thread never touches a view directly (and refreshMenuStatus() is
        // re-run whenever the button is handed back).
        // ------------------------------------------------------------------

        private void setPlayButtonEnabled( final boolean enable ) {
                runOnUiThread( new Runnable() {
                        @Override
                        public void run() {
                                Button play = (Button) findViewById( R.id.menu_launch );
                                if( play != null )
                                        play.setEnabled( enable );

                                if( enable )
                                        refreshMenuStatus();
                        }
                } );
        }

        // ------------------------------------------------------------------
        // A7 build 128 (round 129, user: "fix launcher it too late to start
        // game"): warm launch path + tap dispatch.
        //
        // Before this build every PLAY tap ran its prep ON THE UI THREAD and
        // only then called startActivity():
        //   - addon marker/version checks + plugins.ini + modules.ini writes
        //   - the depth-5 pak search: TWO full-tree walks over the xash root,
        //     one listFiles() per directory through Android FUSE (the same
        //     file system where a single stat costs milliseconds)
        // so the tap visibly froze the launcher before the engine appeared.
        // The warm pass below does exactly that work ONCE, in the background,
        // while the menu is still coming up; after it, the tap path is a
        // HashMap read + startActivity().
        // ------------------------------------------------------------------

        private void warmLaunchCache() {
                final long t0 = android.os.SystemClock.uptimeMillis();

                String cs = cachedPakDir( "cstrike" );
                String hl = cachedPakDir( "valve" );
                String cz = cachedPakDir( "czero" );

                mLaunchPrepDone = true;
                Log.i( TAG, "[A7] build128: launch warm took "
                        + ( android.os.SystemClock.uptimeMillis() - t0 )
                        + " ms (pak cstrike=" + cs + ", valve=" + hl
                        + ", czero=" + cz + ")" );

                // A7 build 138 (round 137 item 2, user: "fix start launch
                // game still too late"): the engine runs in THIS process,
                // so the PLAY tap used to pay for dlopen'ing libSDL2.so
                // (6.5 MB) + libxash.so (11 MB) on top of everything
                // else. Do it here, on the install thread, while the menu
                // is still up - System.loadLibrary is idempotent per
                // process and in the same order as XashActivity's
                // getLibraries(), so the engine activity's own load turns
                // into a cheap no-op hit.
                final long tLibs = android.os.SystemClock.uptimeMillis();
                try {
                        System.loadLibrary( "SDL2" );
                        System.loadLibrary( "xash" );
                        Log.i( TAG, "[A7] build138: engine libs preloaded in the launcher (+"
                                + ( android.os.SystemClock.uptimeMillis() - tLibs )
                                + " ms; the PLAY tap no longer pays the dlopen)" );
                } catch( Throwable t ) {
                        Log.w( TAG, "engine lib preload failed (the engine will load them itself)", t );
                }

                // A7 build 140 (round 140 item 5, user: "when click play
                // should not be too late lagg to start"): engine boot still
                // dlopened the filesystem and the renderer modules ITSELF on
                // the tap path - filesystem_stdio ~2.2 MB and ref_gl4es
                // ~4.9 MB are paid between the tap and the first frame.
                // Warm them here too, after SDL2/xash (their link
                // dependencies), ONE lib per try so a missing or failing
                // load can never block the others - a failure just leaves
                // the original engine-side dlopen in place. Each lib is
                // idempotent per process, so the engine's own load turns
                // into a refcount no-op. The av* decode libs are NOT on the
                // tap path (video playback) and stay lazy.
                final long tMods = android.os.SystemClock.uptimeMillis();
                final String[] tapMods = { "filesystem_stdio", "ref_gl4es",
                        "ref_gles1", "ref_gles3compat" };
                int warmed = 0;
                for( int i = 0; i < tapMods.length; i++ ) {
                        try {
                                System.loadLibrary( tapMods[i] );
                                warmed++;
                        } catch( Throwable t ) {
                                Log.w( TAG, "tap-path lib not warmed: " + tapMods[i] );
                        }
                }
                Log.i( TAG, "[A7] build140: tap-path modules warmed (" + warmed
                        + "/" + tapMods.length + " in "
                        + ( android.os.SystemClock.uptimeMillis() - tMods )
                        + " ms; filesystem + renderers)" );
        }

        // cached form of findGamePakDir(): the walk is the expensive part of
        // the tap path, so the answer is remembered and only re-validated
        // cheaply on later taps (the folder may still be moved/deleted while
        // the launcher sits open, which is what invalidates the entry).
        private String cachedPakDir( String gamedir ) {
                synchronized( mPakDirCache ) {
                        String p = mPakDirCache.get( gamedir );
                        if( p != null ) {
                                File f = new File( getXashDir(), p );
                                if( f.isDirectory() && dirHasPaks( f ))
                                        return p;
                                mPakDirCache.remove( gamedir );
                        }
                }

                String found = findGamePakDir( gamedir );
                synchronized( mPakDirCache ) {
                        // null is stored too: "not found yet" must not make the
                        // next tap walk the whole tree again either
                        mPakDirCache.put( gamedir, found );
                }
                return found;
        }

        /**
         * Single entry point for the three game cards (A7 build 142,
         * round 142 item 2, user: "fix in cs16fresh when launch game
         * don't make verify first , just when open app will verify then
         * will ready to play"): the launch pass - bundle extract, seeds,
         * pak walks, lib warm - belongs ENTIRELY to the open-time pass in
         * proceed(). This method verifies NOTHING: no extract, no seeds,
         * no warm, no filesystem syscall on the UI thread.
         *
         *   1. bundle literally mid-extract (first launch after an update
         *      only) -> queued, fired by the OPEN pass when the bundle
         *      lands on disk,
         *   2. pak-dir answer already cached (the normal case - the open
         *      walk owns it) -> startGameNow() immediately,
         *   3. cache miss (tap inside the first warm seconds) -> ONE
         *      off-thread pak-dir resolve, then start - never the whole
         *      prep chain any more (that duplicated work WAS the
         *      "verify first" he saw between tap and game).
         */
        private void beginLaunch( final String game, final long tapMs ) {
                if( mInstallRunning.get() && !mBundleCurrent ) {
                        // the first launch after every update re-extracts the
                        // bundle; a tap during that window used to hit a
                        // DISABLED button and die silently
                        mPendingLaunchGame = game;
                        mPendingLaunchTapMs = tapMs;
                        Log.i( TAG, "[A7] build141: tap queued - bundle still extracting (game=" + game + ")" );
                        Toast.makeText( this,
                                "Installing game add-ons - starting the game as soon as it is ready",
                                Toast.LENGTH_SHORT ).show();
                        return;
                }

                // build 142: cache READ only - zero I/O on the tap. The open
                // pass pre-fills all three games, so this is the branch a
                // ready launcher takes every time.
                if( peekPakDir( pakDirKeyFor( game )) != null ) {
                        if( mInstallRunning.get())
                                Log.i( TAG, "[A7] build142: tap started while open pass still warming (game="
                                        + game + ", " + ( android.os.SystemClock.uptimeMillis() - tapMs )
                                        + " ms after tap)" );
                        startGameNow( game, tapMs );
                        return;
                }

                // build 142: cache miss - resolve ONLY this game's pak dir on
                // a worker, then start. The old safety net (re-extract addons
                // + seeds + warm HERE on the tap) is GONE - that was the
                // "verify first" at launch. Whatever it guarded runs at app
                // open now; a failed bundle is named by the open-pass log and
                // never re-checked behind his PLAY tap.
                new Thread( new Runnable() {
                        @Override
                        public void run() {
                                try {
                                        cachedPakDir( pakDirKeyFor( game ));
                                } catch( Throwable t ) {
                                        Log.w( TAG, "pak dir resolve failed", t );
                                }

                                Log.i( TAG, "[A7] build142: tap resolved pak dir only - starting (game="
                                        + game + ", " + ( android.os.SystemClock.uptimeMillis() - tapMs )
                                        + " ms after tap)" );
                                runOnUiThread( new Runnable() {
                                        @Override
                                        public void run() {
                                                startGameNow( game, tapMs );
                                        }
                                });
                        }
                }, "pak-resolve" ).start();
        }

        /**
         * A7 build 142 (round 142 item 2): TAP-side pak answer - a plain
         * map READ, deliberately WITHOUT cachedPakDir()'s isDirectory /
         * dirHasPaks re-validation (any syscall behind the tap is a
         * "verify"). Misses are resolved off-thread through the validating
         * cachedPakDir() before startGameNow() runs.
         */
        private String peekPakDir( String gamedir ) {
                synchronized( mPakDirCache ) {
                        return mPakDirCache.get( gamedir );
                }
        }

        /** gamedir key of a startGameNow() game id, for the pak cache. */
        private String pakDirKeyFor( String game ) {
                if( "hl".equals( game ) || "valve".equals( game ))
                        return "valve";
                if( "cz".equals( game ))
                        return "czero";
                return "cstrike";
        }

        private void startGameNow( String game, long tapMs ) {
                if( "hl".equals( game ) || "valve".equals( game ))
                        startHalfLife( tapMs );
                else if( "cz".equals( game ))
                        startCZero( tapMs );
                else
                        startCounterStrike( tapMs );
        }

        private void startCounterStrike( long tapMs ) {
                // A7 build 128: kernels of the old onLaunchPressed() - the prep
                // (addon extract/patch/seeds, pak search) already ran in the
                // warm pass, so this is what a tap actually costs now.
                // A7 build 126 (item 8): Counter-Strike joins the nested /
                // wrong-case pak discovery the HL (build 115) and CZ (build
                // 116) cards already had - "cstrike" passed blind reached the
                // engine as an empty game dir for a zipped
                // xash/cstrike/cstrike/pak0.pak (or a "CS/" folder). Folder
                // really absent: start anyway, the engine's own console
                // explains what's missing (same start-anyway rule as HL/CZ).
                // A7 build 142 (item 2): peek, not cachedPakDir - beginLaunch already
                // resolved the cache (hit, or the worker seeded it), so the tap
                // never validates anything on the UI thread; null falls back
                // to the default gamedir exactly like before
                String pakDir = peekPakDir( "cstrike" );
                if( pakDir == null )
                        pakDir = "cstrike";

                launchEngine( pakDir, null,
                        !isAmxxOn() && !isYapbOn() && !isEbotOn(), tapMs );
        }

        // A tap that arrived while the bundle install was running - fired by
        // the install thread once its gate is open.
        private void firePendingLaunch() {
                final String game = mPendingLaunchGame;
                final long tapMs = mPendingLaunchTapMs;
                if( game == null )
                        return;

                mPendingLaunchGame = null;
                mPendingLaunchTapMs = -1;
                Log.i( TAG, "[A7] build128: queued launch fired "
                        + ( android.os.SystemClock.uptimeMillis() - tapMs )
                        + " ms after tap (game=" + game + ")" );

                // re-enter through beginLaunch(): by now the install thread has
                // released the gate, so this takes the warm/fast path (or the
                // off-thread prep as the safety net)
                runOnUiThread( new Runnable() {
                        @Override
                        public void run() {
                                beginLaunch( game, tapMs );
                        }
                } );
        }

        // ------------------------------------------------------------------
        // append install problems to <xash>/amxx-install-log.txt so we can
        // see the exact reason (EACCES, missing asset, ...) when the user
        // reports a generic "it gave an error"
        // ------------------------------------------------------------------

        private void logInstallError( String source, Throwable t ) {
                try {
                        File dir = getXashDir();
                        if( !dir.isDirectory() )
                                dir.mkdirs();

                        java.io.PrintWriter pw = new java.io.PrintWriter(
                                new java.io.FileWriter( new File( dir, "amxx-install-log.txt" ), true ));
                        pw.println( "--- " + new java.util.Date() + " ---" );
                        pw.println( "source: " + source );
                        pw.println( t.getClass().getName() + ": " + t.getMessage() );
                        StackTraceElement[] st = t.getStackTrace();
                        for( int i = 0; i < st.length && i < 8; i++ )
                                pw.println( "    at " + st[i] );
                        if( t.getCause() != null )
                                pw.println( "cause: " + t.getCause().getClass().getName() + ": " + t.getCause().getMessage() );
                        pw.println();
                        pw.close();
                } catch( Throwable ignored ) {
                        // logging must never crash anything
                }
        }

        // ------------------------------------------------------------------
        // storage access (the game directory lives on shared storage, next to
        // the engine's base dir /storage/emulated/0/xash)
        // ------------------------------------------------------------------

        private boolean hasStorageAccess() {
                if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.R )
                        return Environment.isExternalStorageManager();

                if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.M )
                        return checkSelfPermission( android.Manifest.permission.WRITE_EXTERNAL_STORAGE )
                                == PackageManager.PERMISSION_GRANTED;

                return true;
        }

        private void requestStorageAccess() {
                getPreferences( MODE_PRIVATE ).edit().putBoolean( "permission_asked", true ).apply();
                updateStorageBanner();

                if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ) {
                        // 1) dedicated per-app "All files access" page
                        try {
                                startActivity( new Intent(
                                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                        Uri.parse( "package:" + getPackageName() )));
                                return;
                        } catch( Exception ignored ) {
                        }

                        // 2) the general "All files access" list (some OEM skins
                        //    lack the per-app page)
                        try {
                                startActivity( new Intent(
                                        Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION ));
                                return;
                        } catch( Exception ignored ) {
                        }

                        // 3) app details - "All files access" is reachable from
                        //    Special access on any device (Samsung One UI, ...)
                        try {
                                startActivity( new Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.parse( "package:" + getPackageName() )));
                        } catch( Exception ex ) {
                                Log.w( TAG, "no storage settings page", ex );
                        }
                } else if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ) {
                        requestPermissions( new String[] { android.Manifest.permission.WRITE_EXTERNAL_STORAGE }, 1 );
                }
        }

        private void updateStorageBanner() {
                android.view.View banner = findViewById( R.id.storage_banner );
                if( banner != null )
                        banner.setVisibility( hasStorageAccess()
                                ? android.view.View.GONE : android.view.View.VISIBLE );
        }

        @Override
        public void onRequestPermissionsResult( int requestCode, String[] permissions, int[] grantResults ) {
                super.onRequestPermissionsResult( requestCode, permissions, grantResults );

                if( requestCode == 1 ) {
                        if( grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED ) {
                                mPermissionAsked = false;
                                proceed();
                        } else {
                                // A7 build 126 (item 9, user: "allow access
                                // storage files for androids from 5 to 16"):
                                // after a "don't ask again" denial the system
                                // never shows the dialog again - a bare
                                // requestPermissions() would deny forever and
                                // this toast would repeat on every tap. Route
                                // to the app settings page where the storage
                                // switch lives (the same page the Android 11+
                                // flow already opens).
                                if( !shouldShowRequestPermissionRationale(
                                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE )) {
                                        try {
                                                startActivity( new Intent(
                                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                        Uri.parse( "package:" + getPackageName() )));
                                                return;
                                        } catch( Exception ignored ) {
                                        }
                                }
                                Toast.makeText( this,
                                        "Cannot install addons without storage access",
                                        Toast.LENGTH_LONG ).show();
                        }
                }
        }

        // ------------------------------------------------------------------
        // AMX Mod X addons auto-install
        //
        // assets/addons.zip mirrors the game directory layout:
        //   cstrike/addons/metamod/...
        //   cstrike/addons/amxmodx/...
        // It is extracted into /storage/emulated/0/xash so that the patched
        // engine (su.xash.engine.test) finds the metamod library when the
        // server gamelib is loaded and relays plugin loading to AMX Mod X.
        // ------------------------------------------------------------------

        private File getXashDir() {
                // A7 build 126 (item 10, user: "fix correct place for storage
                // all devices for not getting errors"): the canonical shared
                // storage root on every Android 5-16 device is
                // /storage/emulated/0 - launcher, engine XASH3D_BASEDIR and
                // the add-on installer must all agree on it.
                // Environment.getExternalStorageDirectory() is the sanctioned
                // way there; the literal path is the fallback for OEM builds
                // where the storage provider misbehaves.
                try {
                        File root = Environment.getExternalStorageDirectory();
                        if( root != null )
                                return new File( root, "xash" );
                } catch( Throwable ignored ) {
                }
                return new File( "/storage/emulated/0/xash" );
        }

        private void extractAddonsIfNeeded() throws Exception {
                extractAddons( false );
        }

        /**
         * force = true is the manual "Re-install addons" button: restore
         * every bundled file to stock, configs included. Files this app
         * never shipped (the user's own plugins, waypoints, saved plugin
         * data) are never touched on any path - force only resets files
         * that come from our own bundle.
         */
        private void extractAddons( boolean force ) throws Exception {
                // bot packs (YaPB conf/graphs, EBot configs) ship as individual
                // assets, not inside addons.zip - sync them onto disk first
                Map<String, String> manifest = loadShippedManifest();

                // pre-manifest installs: first run of a build that tracks file
                // checksums. The previous build extracted everything on disk,
                // so bootstrap the manifest and refresh the whole bundle once,
                // exactly like the old update path did - from then on user
                // edits are protected by their checksums.
                boolean bootstrap = manifest.isEmpty() && (
                        getPreferences( MODE_PRIVATE ).getString( "addons_version", null ) != null
                        || getPreferences( MODE_PRIVATE ).getString( "bot_assets_version", null ) != null );
                boolean full = force || bootstrap;
                // only the user-pressed reinstall button may reset configs
                // to stock; a bootstrap (upgrade from a pre-manifest build)
                // keeps them, exactly like the old update path did
                boolean factoryReset = force;

                // cstrike addons (AMX Mod X for Counter-Strike) -- this app
                // installs ONLY the counter-strike addon pack. The valve
                // (Half-Life) AMX addons are handled by the engine app itself
                // (Xash3D FWGS (AMXX)) at valve game launch since v9.
                extractBotAssetsIfNeeded( full, factoryReset, manifest );
                // A7 build 137 (item 4): the marker can no longer be a native
                // library - since this build NO .so is written to /sdcard at
                // all (metamod/AMXX/regamedll load from the APK lib dir), so
                // the arm64-only libmetamod marker would never exist on a
                // fresh install and the gate would re-extract every launch.
                // plugins.ini ships in the same bundle and is ABI-neutral.
                extractZipIfNeeded( "addons.zip", "addons_version",
                        "cstrike/addons/metamod/plugins.ini",
                        full, factoryReset, manifest );
                saveShippedManifest( manifest );

                // A7 build 139 (item 5): the collage refresh below is
                // stamp-gated, not bundle-CRC gated - a warm install must
                // run it too (see refreshMenuArt)
                refreshMenuArt();

                // A7 build 140 (round 140 item 2, "other show another
                // color weird"): the GameUI palette has the same warm-gap
                // the collage had - the bundle's always-refresh rule for
                // gfx/shell/colors.lst lives inside mayOverwrite and only
                // runs DURING an extraction, which the CRC gate skips on
                // every launch after the first. A stale on-disk palette is
                // the only thing that repaints the menu away from the
                // shipped colors (UI_ApplyCustomColors parses it once at
                // UI_Init), so force it once per bundle, gate or not.
                refreshMenuColors();

                // the stale-file cleanup is GONE. It deleted the
                // ProBaseBuilder file set (bb plugins, Pro_basebuilder.ini and
                // scripting/include/basebuilder.inc) on EVERY launch, so anything
                // the user installed with those names vanished at the next app
                // start. The one-off leftover purge already ran on every launch
                // of the earlier builds, fresh installs never had those files,
                // and basebuilder.inc is package content again - keeping the
                // purge contradicted the package itself.

                // keep plugins.ini in sync with this app's nativeLibraryDir
                // (YaPB as metamod plugin) - must run on every launch in case the
                // engine/game ABI layout changed, and it is cheap anyway.
                patchPluginsIni();

                // re-apply any custom module registration lines (a bundle
                // refresh rewrites modules.ini and would otherwise drop them)
                ModuleInstaller.reapplyAll();
        }

        // A7 build 136 (item 2): content-addressed version stamp for a
        // bundled zip asset. CRC32 of assets/<assetName>, cached - the
        // first computation costs one asset read (replacing the old full
        // sha256/zip walk), afterwards it is a HashMap hit. Keeps the
        // "amxx-1.9.0.5314-..." prefix the on-device pref stores, with
        // crc<hex> where the app version used to be.
        private static final java.util.HashMap<String, String> sAssetGateCache =
                new java.util.HashMap<String, String>();

        private String assetGateVersion( String assetName ) {
                synchronized( sAssetGateCache ) {
                        String hit = sAssetGateCache.get( assetName );
                        if( hit != null )
                                return hit;
                }

                String v;
                try {
                        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                        InputStream in = getAssets().open( assetName );
                        try {
                                byte[] buf = new byte[65536];
                                int n;
                                while(( n = in.read( buf )) > 0 )
                                        crc.update( buf, 0, n );
                        } finally {
                                in.close();
                        }
                        v = "amxx-1.9.0.5314-crc"
                                + Long.toHexString( crc.getValue())
                                + "-b73-gamemenu";
                } catch( Throwable t ) {
                        // asset unreadable: fall back to the old behaviour
                        // (app-version tied stamp) rather than skipping the
                        // extraction gate
                        v = "amxx-1.9.0.5314-" + BuildConfig.VERSION_NAME
                                + "-b73-gamemenu";
                }

                Log.i( TAG, "[A7] build136: addons gate " + assetName + " = " + v );

                synchronized( sAssetGateCache ) {
                        sAssetGateCache.put( assetName, v );
                }
                return v;
        }

        // ------------------------------------------------------------------
        // A7 build 137 (item 4, user: "fix libs in xash folder should not be
        // there like amxmodx metamod, only inside apk finding gamedir, for
        // all androids ... fix also for reinstall addons sometime it do
        // mixed and put dll .so on xash"): no native library may live under
        // /sdcard/xash any more. Metamod, AMX Mod X and the regamedll all
        // load from the APK's nativeLibraryDir (XASH3D_GAMELIBDIR, one ABI
        // only - a package only ever contains the device's own ABI), so the
        // three historical native dirs are cleaned HERE, before the extract
        // gate: a 136-era install passes the gate + marker yet still carries
        // those old .so, and a reinstall that stopped halfway left a mix of
        // arm64/armv7 files behind (the "reinstall puts dll .so on xash"
        // report). Every candidate is ELF-verified with
        // ModuleInstaller.detectAbi() first, so the log states exactly which
        // ABI was on disk versus which ABI this APK serves.
        // ------------------------------------------------------------------
        private static volatile boolean sPurgedSdLibsThisRun = false;

        /** the single ABI this APK packages, read from its own lib dir. */
        private String detectApkAbi() {
                try {
                        File[] libs = new File( getApplicationInfo().nativeLibraryDir ).listFiles();
                        if( libs != null ) {
                                for( File f : libs ) {
                                        if( f.getName().endsWith( ".so" )) {
                                                String a = ModuleInstaller.detectAbi( f );
                                                if( a != null )
                                                        return a;
                                        }
                                }
                        }
                } catch( Throwable t ) {
                }
                return null;
        }

        private void purgeSdCardLibs() {
                if( sPurgedSdLibsThisRun )
                        return;
                sPurgedSdLibsThisRun = true;

                String apkAbi = detectApkAbi();

                // A7 build 137: the Modules tab installs user-owned .so into
                // exactly these directories and records them in
                // <gamedir>/addons/.custom-modules - those are the USER's files
                // (registered bases are never touched), everything else in here
                // is shipped content that must move to the APK
                java.util.HashSet<String> customs = new java.util.HashSet<String>();
                try {
                        for( ModuleInstaller.Entry e : ModuleInstaller.readRegistry( "cstrike" )) {
                                if( e.base != null )
                                        customs.add( e.base.toLowerCase( Locale.US ));
                        }
                } catch( Throwable t ) {
                }

                String[] roots = {
                        "cstrike/addons/metamod/dlls",
                        "cstrike/addons/amxmodx/dlls",
                        "cstrike/addons/amxmodx/modules",
                };
                int removed = 0, foreign = 0, kept = 0;

                for( String r : roots ) {
                        File[] files = new File( getXashDir(), r ).listFiles();
                        if( files == null )
                                continue;

                        for( File f : files ) {
                                if( !f.isFile())
                                        continue;

                                String n = f.getName().toLowerCase( Locale.ROOT );
                                if( !n.endsWith( ".so" ) && !n.endsWith( ".dll" ))
                                        continue;

                                // registered by the Modules tab: the user's own
                                // file - the shipped-library purge skips it
                                if( customs.contains(
                                        ModuleInstaller.baseName( f.getName()).toLowerCase( Locale.US ))) {
                                        kept++;
                                        Log.i( TAG, "[A7] build137: kept user module " + r + "/" + f.getName());
                                        continue;
                                }

                                String abi = ModuleInstaller.detectAbi( f );
                                if( apkAbi != null && abi != null && !apkAbi.equals( abi ))
                                        foreign++;

                                Log.i( TAG, "[A7] build137: purge sdcard lib " + r + "/"
                                        + f.getName() + " (abi " + ( abi != null ? abi : "unknown" )
                                        + ", apk abi " + ( apkAbi != null ? apkAbi : "unknown" ) + ")" );

                                if( f.delete())
                                        removed++;
                                else
                                        Log.w( TAG, "[A7] build137: could not remove " + f.getAbsolutePath());
                        }
                }

                Log.i( TAG, "[A7] build137: purge sdcard lib done - " + removed
                        + " removed, wrong-abi " + foreign + ", customs kept "
                        + kept + ", apk abi " + ( apkAbi != null ? apkAbi : "unknown" ));
        }

        private int extractZipIfNeeded( String assetName, String prefKey,
                String markerRelPath, boolean full, boolean factoryReset,
                Map<String, String> manifest ) throws Exception {
                SharedPreferences prefs = getPreferences( MODE_PRIVATE );

                // A7 build 137: BEFORE the gate - a warm (unchanged) install
                // must clean the old .so too, else only a bundle change
                // would ever purge them
                purgeSdCardLibs();

                String installed = prefs.getString( prefKey, null );

                File markerLib = new File( getXashDir(), markerRelPath );

                // A7 build 136 (item 2): gate on the asset's content crc,
                // not on the app version - see assetGateVersion()
                String gateVersion = assetGateVersion( assetName );

                if( !full && gateVersion.equals( installed ) && markerLib.isFile() ) {
                        Log.i( TAG, "AMXX addons up to date (" + assetName + ": " + gateVersion + ")" );
                        return 0;
                }

                File dest = getXashDir();
                String destCanon = dest.getCanonicalPath();
                byte[] buffer = new byte[65536];
                int count = 0;

                InputStream in = getAssets().open( assetName );
                ZipInputStream zis = new ZipInputStream( in );

                ZipEntry entry;
                int libSkipped = 0;
                while(( entry = zis.getNextEntry() ) != null ) {
                        if( entry.isDirectory() )
                                continue;

                        String rel = entry.getName();
                        File outFile = new File( dest, rel );

                        // zip-slip guard
                        if( !outFile.getCanonicalPath().startsWith( destCanon + File.separator )) {
                                Log.w( TAG, "skipping suspicious zip entry: " + rel );
                                continue;
                        }

                        // A7 build 137 (item 4): NATIVE LIBRARIES ARE NEVER
                        // WRITTEN TO /sdcard any more - the engine sources
                        // metamod/AMXX/regamedll from the APK's
                        // nativeLibraryDir (single ABI, set in
                        // XashActivity.getArguments -> XASH3D_GAMELIBDIR), so
                        // a gamedir copy could only ever go stale or get
                        // mixed with the wrong ABI on a reinstall. The zips
                        // keep their entries (content must stay identical to
                        // the reference); only the EXTRACTION skips them and
                        // deletes any leftover from an older install.
                        if( rel.endsWith( ".so" ) || rel.endsWith( ".dll" )) {
                                if( outFile.isFile() && outFile.delete())
                                        Log.i( TAG, "[A7] build137: removed leftover sdcard ll " + rel );
                                libSkipped++;
                                zis.closeEntry();
                                continue;
                        }

                        // user-owned or user-edited file: keep the disk copy
                        if( !mayOverwrite( outFile, rel, manifest, full,
                                factoryReset )) {
                                Log.i( TAG, "keeping user file: " + rel );
                                zis.closeEntry();
                                continue;
                        }

                        File parent = outFile.getParentFile();
                        if( parent != null && !parent.isDirectory() )
                                parent.mkdirs();

                        FileOutputStream fos = new FileOutputStream( outFile );
                        int n;
                        while(( n = zis.read( buffer )) > 0 )
                                fos.write( buffer, 0, n );
                        fos.close();

                        // native libraries may need the exec bit depending on the filesystem
                        if( outFile.getName().endsWith( ".so" ))
                                outFile.setExecutable( true, false );

                        // remember what we wrote so later updates can tell our
                        // files apart from user edits
                        manifest.put( rel, sha256Of( outFile ));

                        count++;
                        zis.closeEntry();
                }
                zis.close();

                // A7 build 137 (item 4): one proof line - how many library
                // entries the bundle carried versus how many landed on
                // /sdcard (always 0 from this build on)
                Log.i( TAG, "[A7] build137: addons zip " + libSkipped
                        + " libs served from APK, 0 written to /sdcard" );

                prefs.edit().putString( prefKey, gateVersion ).apply();
                Log.i( TAG, "AMXX addons extracted (" + assetName + "): " + count
                        + " files -> " + dest.getAbsolutePath() );
                Toast.makeText( this, "AMX Mod X addons installed (" + assetName + ": "
                        + count + " files)", Toast.LENGTH_SHORT ).show();
                return count;
        }

        // ------------------------------------------------------------------
        // A7 build 139 (item 5, user: "fix background still with hd, find
        // original counter strike background then fix it"): mayOverwrite's
        // "always refresh the menu collage" rules only run INSIDE an
        // extraction, and a warm install - unchanged assets/addons.zip CRC
        // - skips extraction entirely (extractZipIfNeeded's gate above).
        // So the stale HD collage an old game-data package or an early
        // release left in the gamedir (resource/BackgroundLayout.txt naming
        // the 25'th-anniversary 3840x1600 21_9_* tiles, plus those tiles on
        // disk) survived every update and kept rendering instead of the
        // shipped ORIGINAL 1280x720 collage. Stamp-gated one-time forced
        // refresh: rewrite the shipped layout + tiles straight from the
        // bundle regardless of the gate, then delete every background tile
        // the bundle does not ship (the HD 21_9_* set) and the HD layout
        // files. Menu art only - no configs, no user data, nothing else in
        // the gamedir is touched. No stamp on failure: it retries next
        // launch. Log line is the round's scan marker.
        // ------------------------------------------------------------------
        private void refreshMenuArt() {
                SharedPreferences prefs = getPreferences( MODE_PRIVATE );
                final String STAMP = "b139";
                if( STAMP.equals( prefs.getString( "menu_art_version", "" ) ) )
                        return;

                final String prefix = "cstrike/resource/";
                final String layoutRel = prefix + "BackgroundLayout.txt";
                final String tilesPrefix = prefix + "background/";
                java.util.HashSet<String> shipped = new java.util.HashSet<>();
                byte[] buffer = new byte[65536];
                int written = 0;
                int stale = 0;

                try {
                        File dest = getXashDir();
                        String destCanon = dest.getCanonicalPath();

                        InputStream in = getAssets().open( "addons.zip" );
                        ZipInputStream zis = new ZipInputStream( in );
                        ZipEntry entry;
                        while(( entry = zis.getNextEntry() ) != null ) {
                                if( entry.isDirectory() )
                                        continue;

                                String rel = entry.getName();
                                boolean tile = rel.startsWith( tilesPrefix )
                                        && rel.endsWith( ".tga" );
                                if( !tile && !rel.equals( layoutRel )) {
                                        zis.closeEntry();
                                        continue;
                                }

                                File outFile = new File( dest, rel );
                                if( !outFile.getCanonicalPath().startsWith(
                                        destCanon + File.separator )) {
                                        Log.w( TAG, "skipping suspicious zip entry: " + rel );
                                        zis.closeEntry();
                                        continue;
                                }

                                File parent = outFile.getParentFile();
                                if( parent != null && !parent.isDirectory() )
                                        parent.mkdirs();

                                FileOutputStream fos = new FileOutputStream( outFile );
                                int n;
                                while(( n = zis.read( buffer )) > 0 )
                                        fos.write( buffer, 0, n );
                                fos.close();

                                shipped.add( rel );
                                written++;
                                zis.closeEntry();
                        }
                        zis.close();

                        // stale tiles: anything sitting in the background
                        // folder that the shipped layout set does not carry
                        // (the HD 21_9_* set from an old package) - menu art
                        // by location, never user content
                        File tileDir = new File( dest, tilesPrefix );
                        File[] leftovers = tileDir.listFiles();
                        if( leftovers != null ) {
                                for( File f : leftovers ) {
                                        if( !f.isFile() || !f.getName().endsWith( ".tga" ))
                                                continue;
                                        String rel = tilesPrefix + f.getName();
                                        if( !shipped.contains( rel ) && f.delete())
                                                stale++;
                                }
                        }

                        // and the HD layouts themselves: mainui only reads
                        // them behind GFL_HD_BACKGROUND, but a stale copy
                        // must not stay in the gamedir at all
                        String[] hdLayouts = {
                                prefix + "HD_BackgroundLayout.txt",
                                prefix + "HD_BackgroundLoadingLayout.txt"
                        };
                        for( String rel : hdLayouts ) {
                                File f = new File( dest, rel );
                                if( f.isFile() && f.delete())
                                        stale++;
                        }

                        prefs.edit().putString( "menu_art_version", STAMP ).apply();
                        Log.i( TAG, "[A7] build139: menu background art refreshed ("
                                + written + " shipped written, " + stale
                                + " stale removed)" );
                } catch( Throwable t ) {
                        // no stamp on failure -> retries on the next launch
                        Log.w( TAG, "[A7] build139: menu background art refresh failed", t );
                }
        }

        // ------------------------------------------------------------------
        // A7 build 140 (round 140 item 2): the GameUI palette, forced once
        // regardless of the extraction gate.
        //
        // UI_ApplyCustomColors() (mainui, BaseMenu.cpp) parses
        // gfx/shell/colors.lst ONCE at UI_Init and is the only writer of
        // the palette besides the coded defaults - so a stale on-disk copy
        // repaints the whole menu (the round-131 orange rows report). The
        // bundle's mayOverwrite() says the shipped palette is "always
        // refreshed", but that rule only runs INSIDE an extraction, and
        // extractZipIfNeeded's warm gate (CRC32 of the asset + marker) skips
        // the extraction on every launch after the first - exactly the gap
        // refreshMenuArt closed for the collage in build 139. Same pattern
        // here: stamp-gated one-time rewrite of the shipped colors.lst for
        // BOTH gamedirs (cstrike = the CS menu, valve = the HL menu) read
        // straight from the two bundle zips. Palette only - configs, art,
        // anything else in the gamedir is untouched. No stamp on failure:
        // it retries on the next launch.
        // ------------------------------------------------------------------
        private void refreshMenuColors() {
                SharedPreferences prefs = getPreferences( MODE_PRIVATE );
                final String STAMP = "b140";
                if( STAMP.equals( prefs.getString( "menu_colors_version", "" ) ) )
                        return;

                byte[] buffer = new byte[65536];
                int written = 0;

                try {
                        File dest = getXashDir();
                        String destCanon = dest.getCanonicalPath();
                        String[] zips = { "addons.zip", "valve-addons.zip" };

                        for( int zi = 0; zi < zips.length; zi++ ) {
                                InputStream in = getAssets().open( zips[zi] );
                                ZipInputStream zis = new ZipInputStream( in );
                                ZipEntry entry;
                                while(( entry = zis.getNextEntry() ) != null ) {
                                        if( entry.isDirectory())
                                                continue;

                                        String rel = entry.getName();
                                        if( !rel.endsWith( "gfx/shell/colors.lst" )) {
                                                zis.closeEntry();
                                                continue;
                                        }

                                        File outFile = new File( dest, rel );
                                        if( !outFile.getCanonicalPath().startsWith(
                                                destCanon + File.separator )) {
                                                Log.w( TAG, "skipping suspicious zip entry: " + rel );
                                                zis.closeEntry();
                                                continue;
                                        }

                                        File parent = outFile.getParentFile();
                                        if( parent != null && !parent.isDirectory())
                                                parent.mkdirs();

                                        FileOutputStream fos = new FileOutputStream( outFile );
                                        int n;
                                        while(( n = zis.read( buffer )) > 0 )
                                                fos.write( buffer, 0, n );
                                        fos.close();

                                        written++;
                                        zis.closeEntry();
                                }
                                zis.close();
                        }

                        prefs.edit().putString( "menu_colors_version", STAMP ).apply();
                        Log.i( TAG, "[A7] build140: shipped colors.lst refreshed ("
                                + written + " gamedir palettes written)" );
                } catch( Throwable t ) {
                        // no stamp on failure -> retries on the next launch
                        Log.w( TAG, "[A7] build140: colors.lst refresh failed", t );
                }
        }

        // ------------------------------------------------------------------
        // file ownership for the addon update paths
        //
        // every file this app writes into the game tree is recorded with its
        // SHA-256 in a manifest next to the metamod markers (on disk, so it
        // survives app reinstalls like they do). A later update may then:
        //   - refresh a bundled file that is untouched since we wrote it
        //   - keep a bundled file the user edited (checksum mismatch)
        //   - never touch a file we never wrote (user's own addons)
        // The "Re-install addons" button passes full = factoryReset = true
        // and resets every bundled file to stock, configs included - the one
        // way back to a stock bundle. The one-time bootstrap (upgrade from a
        // pre-manifest build) passes full only: it refreshes the bundle but
        // keeps user configs, exactly like the old update path did.
        // ------------------------------------------------------------------

        /** true when the bundle may write rel over the disk copy. */
        private boolean mayOverwrite( File outFile, String rel,
                Map<String, String> manifest, boolean full,
                boolean factoryReset ) {
                // analyzed/user-provided waypoint files win over the shipped
                // pack on every path, reinstall included
                if( outFile.isFile() && rel.endsWith( ".ewp" ))
                        return false;

                // fvault vaults (ranks/points/packs plugins save) are player
                // progress and must never be overwritten on any path,
                // reinstall included. The Zombie Plague X seeds are created
                // at runtime by ensureZpxVaultSeeds() only (no zip ships
                // them), so nothing in a bundle should ever land here -
                // this guard keeps that true even if a zip regains them.
                if( outFile.isFile() && rel.contains( "/addons/amxmodx/data/file_vault/" ))
                        return false;

                // modules.ini is the module loader list and MUST match the
                // shipped .so set (it enables sqlite, which provides the
                // "sqlx" class that plugins like amx_gag require) - always
                // refreshed
                if( rel.endsWith( "modules.ini" ))
                        return true;

                // hamdata.ini is the hamsandwich module's vtable table and
                // MUST match the shipped module: without its entries every
                // ReGameDLL hook the module knows fails as "Function ...
                // not configured in hamdata.ini" and the requesting plugin
                // kills itself at load. Not user data - always refreshed.
                if( rel.endsWith( "hamdata.ini" ))
                        return true;

                // the menu background: old game-data packages ship the
                // Half-Life splash.bmp under cstrike/gfx/shell and the
                // engine prefers the gamedir copy, so the CS 1.6 collage
                // never showed ("cs16 client looks like Half-Life main
                // ui"). Menu art, not user data - always refreshed.
                if( rel.endsWith( "gfx/shell/splash.bmp" ))
                        return true;

                // the GameUI palette: UI_ApplyCustomColors() parses
                // gfx/shell/colors.lst once at UI_Init and is the only thing
                // that repaints the menu away from the coded defaults (the
                // round-131 screenshots show the rows drawn ORANGE) - so a
                // stale on-disk file wins over everything we fixed in code.
                // Shipped app art, never user data: always refreshed even
                // for a disk copy the manifest never recorded.
                if( rel.endsWith( "gfx/shell/colors.lst" ))
                {
                        if( outFile.isFile() )
                                Log.i( TAG, "[A7] build130: refreshing shipped colors.lst " + rel );
                        return true;
                }

                // the real CS 1.6 menu collage + logo (video reference):
                // ship resource/BackgroundLayout.txt, the 1280x720 tiles
                // and resource/game_menu.tga, and let them replace whatever
                // the game-data package left in the gamedir. Menu art,
                // not user data - always refreshed.
                if( rel.endsWith( "BackgroundLayout.txt" ) ||
                        rel.endsWith( "_loading.tga" ) ||
                        rel.endsWith( "game_menu.tga" ))
                        return true;

                // the recreated GameUI menu rows: an original game-data
                // package's GameMenu.res (2017 Android pack, quick-join row
                // carrying a dead "engine Connect" command) predates this
                // project's staged copy and must not win - menu behavior,
                // not user data - always refreshed.
                if( rel.endsWith( "resource/GameMenu.res" ))
                        return true;

                // the GameUI localization: every menu label resolves through
                // L() against resource/*_english.txt at startup, and a stale
                // disk copy inserts EMPTY values which render as nothing at
                // all - the round-130 "menu text is blank" report. Shipped
                // app assets, never user-editable data: always refreshed,
                // even for a disk copy the manifest never recorded (the path
                // below would keep that one forever).
                if( rel.endsWith( "_english.txt" ))
                {
                        if( outFile.isFile() )
                                Log.i( TAG, "[A7] build129: refreshing shipped localization " + rel );
                        return true;
                }

                // the reinstall button resets everything bundled to stock,
                // configs included: it is the one way back to a stock bundle
                if( factoryReset )
                        return true;

                // on updates (and the one-time bootstrap) amxx configs are
                // user-owned data: the plugin list, admins (users.ini),
                // cvars... they are edited after the first install and an
                // update must not clobber them
                if( outFile.isFile() && rel.contains( "/addons/amxmodx/configs/" ))
                        return false;

                if( full || !outFile.isFile() )
                        return true;

                String recorded = manifest.get( rel );
                if( recorded == null )
                        return false; // on disk but never written by us: user's

                try {
                        // unchanged since we wrote it -> bundled update wins
                        return recorded.equalsIgnoreCase( sha256Of( outFile ));
                } catch( Throwable t ) {
                        return false; // unreadable -> keep the disk copy
                }
        }

        private File shippedManifestFile() {
                return new File( getXashDir(),
                        "cstrike/addons/metamod/.cs16client-shipped" );
        }

        private Map<String, String> loadShippedManifest() {
                Map<String, String> map = new HashMap<String, String>();
                try {
                        File f = shippedManifestFile();
                        if( !f.isFile() )
                                return map;
                        BufferedReader r = new BufferedReader( new FileReader( f ));
                        String line;
                        while(( line = r.readLine() ) != null ) {
                                // "<64 hex> <relpath>"
                                if( line.length() > 65 && line.charAt( 64 ) == ' ' )
                                        map.put( line.substring( 65 ), line.substring( 0, 64 ));
                        }
                        r.close();
                } catch( Throwable t ) {
                        Log.w( TAG, "shipped manifest unreadable", t );
                }
                return map;
        }

        private void saveShippedManifest( Map<String, String> map ) {
                try {
                        File f = shippedManifestFile();
                        if( f.getParentFile() != null )
                                f.getParentFile().mkdirs();
                        PrintWriter pw = new PrintWriter( new FileWriter( f, false ));
                        for( Map.Entry<String, String> e : map.entrySet() )
                                pw.println( e.getValue() + " " + e.getKey() );
                        pw.close();
                } catch( Throwable t ) {
                        Log.w( TAG, "cannot write shipped manifest", t );
                }
        }

        private static String sha256Of( File f ) throws Exception {
                MessageDigest md = MessageDigest.getInstance( "SHA-256" );
                FileInputStream in = new FileInputStream( f );
                try {
                        byte[] buf = new byte[65536];
                        int n;
                        while(( n = in.read( buf )) > 0 )
                                md.update( buf, 0, n );
                } finally {
                        in.close();
                }
                StringBuilder sb = new StringBuilder();
                for( byte b : md.digest() )
                        sb.append( Character.forDigit(( b >> 4 ) & 0xF, 16 ))
                          .append( Character.forDigit( b & 0xF, 16 ));
                return sb.toString();
        }

        /**
         * Rewrite metamod plugins.ini after the addons extraction.
         *
         * - The AMX Mod X core plugin keeps its relative path (it lives inside
         *   the extracted addons tree).
         * - YaPB is loaded as a METAMOD PLUGIN now (-dll @yapb removed:
         *   the yapb wrapper does not re-export entity spawn symbols, so the
         *   engine failed every map entity with "No spawn function"). YaPB
         *   needs an absolute path to its library inside this app's
         *   nativeLibraryDir, which is only known at runtime - so we write it
         *   here, right after the extraction.
         */
        private void patchPluginsIni() {
                try {
                        File ini = new File( getXashDir(), "cstrike/addons/metamod/plugins.ini" );
                        File dir = ini.getParentFile();
                        if( dir == null )
                                return;
                        dir.mkdirs();

                        // AMX Mod X and YaPB are independent switches (both can
                        // be enabled at the same time). The engine app gates each
                        // plugins.ini line through a marker file next to the ini,
                        // because this app cannot read the engine's SharedPreferences.
                        // When BOTH are disabled the engine bypasses metamod entirely
                        // (XASH3D_DISABLE_AMXX) and the ini contents do not matter.
                        boolean amxxEnabled = !new File( dir, "amxmodx.disabled" ).isFile();
                        boolean yapbEnabled = !new File( dir, "yapb.disabled" ).isFile();
                        boolean ebotEnabled = !new File( dir, "ebot.disabled" ).isFile();

                        StringBuilder sb = new StringBuilder();
                        sb.append( ";;;\n" );
                        sb.append( "; Metamod plugin list for Android (xash3d-fwgs)\n" );
                        sb.append( "; AMX Mod X core:\n" );
                        sb.append( ";;;\n" );

                        // preserve plugin lines the USER added to plugins.ini.
                        // Everything that is not one of the two lines this app
                        // manages survives the rewrite (metamod ignores what it
                        // cannot load, so a stale custom line is harmless).
                        java.util.List<String> custom = new java.util.ArrayList<String>();
                        try {
                                java.io.BufferedReader br = new java.io.BufferedReader(
                                        new java.io.FileReader( ini ) );
                                String line;
                                while(( line = br.readLine()) != null ) {
                                        String t = line.trim();
                                        if( !t.startsWith( "linux " ) )
                                                continue;
                                        if( t.contains( "libmm_amxmodx.so" ) ||
                                                t.contains( "libyapb_android_" ) ||
                                                t.contains( "libebot_android_" ) )
                                                continue;
                                        if( !custom.contains( t ) )
                                                custom.add( t );
                                }
                                br.close();
                        } catch( Throwable ignored ) {
                                // no previous ini / unreadable - nothing to preserve
                        }

                        if( amxxEnabled ) {
                                sb.append( "linux addons/amxmodx/dlls/libmm_amxmodx.so\n" );
                        } else {
                                sb.append( "; disabled by the AMX Mod X switch: addons/metamod/amxmodx.disabled\n" );
                        }

                        // YaPB bot plugin (absolute path, ABI-dependent). EBot
                        // and YaPB both fill the bot slots, so only one can be
                        // loaded: the switches keep them exclusive and ebot
                        // wins in here if both markers are somehow absent.
                        if( yapbEnabled && !ebotEnabled ) {
                                String nativeDir = getApplicationInfo().nativeLibraryDir;
                                String[] abis = android.os.Build.SUPPORTED_ABIS;
                                String yapbName = null;
                                if( abis != null && abis.length > 0 && abis[0].contains( "arm64" ) )
                                        yapbName = "libyapb_android_arm64.so";
                                else if( abis != null && abis.length > 0 )
                                        yapbName = "libyapb_android_armv7l.so";

                                if( yapbName != null && nativeDir != null ) {
                                        File yapb = new File( nativeDir, yapbName );
                                        if( yapb.isFile() ) {
                                                sb.append( ";\n; YaPB bot (as metamod plugin):\n" );
                                                sb.append( "linux " ).append( yapb.getAbsolutePath() ).append( "\n" );
                                        }
                                }
                        } else if( ebotEnabled ) {
                                sb.append( "; YaPB bots paused: EBot is enabled (only one bot fills the slots)\n" );
                        } else {
                                sb.append( "; YaPB bots disabled by the YaPB switch: addons/metamod/yapb.disabled\n" );
                        }

                        // EBot bot plugin (absolute path, ABI-dependent)
                        if( ebotEnabled ) {
                                String nativeDir = getApplicationInfo().nativeLibraryDir;
                                String[] abis = android.os.Build.SUPPORTED_ABIS;
                                String ebotName = null;
                                if( abis != null && abis.length > 0 && abis[0].contains( "arm64" ) )
                                        ebotName = "libebot_android_arm64.so";
                                else if( abis != null && abis.length > 0 )
                                        ebotName = "libebot_android_armv7l.so";

                                if( ebotName != null && nativeDir != null ) {
                                        File ebot = new File( nativeDir, ebotName );
                                        if( ebot.isFile() ) {
                                                sb.append( ";\n; EBot bot (as metamod plugin):\n" );
                                                sb.append( "linux " ).append( ebot.getAbsolutePath() ).append( "\n" );
                                        }
                                }
                        } else {
                                sb.append( "; EBot bots disabled by the EBot switch: addons/metamod/ebot.disabled\n" );
                        }

                        if( !custom.isEmpty() ) {
                                sb.append( ";\n; custom plugin lines (kept from the previous plugins.ini):\n" );
                                for( String t : custom )
                                        sb.append( t ).append( "\n" );
                        }

                        java.io.PrintWriter pw = new java.io.PrintWriter( new java.io.FileWriter( ini, false ) );
                        pw.print( sb.toString() );
                        pw.close();
                        Log.i( TAG, "plugins.ini patched: " + ini.getAbsolutePath()
                                + " (amxx=" + amxxEnabled + ", yapb=" + yapbEnabled
                                + ", ebot=" + ebotEnabled + ")" );
                } catch( Throwable t ) {
                        Log.w( TAG, "plugins.ini patch failed", t );
                }
        }

        // ------------------------------------------------------------------
        // engine launch
        // ------------------------------------------------------------------

        // build 114 merge: the engine (XashActivity) lives in THIS app now, so
        // there is no su.xash.engine package to look up and no download-page
        // fallback - the intent targets the local class directly.
        // build 115 (back kick): NO FLAG_ACTIVITY_CLEAR_TASK and NO finish() -
        // the launcher must stay under XashActivity in the task stack so the
        // game's back button returns HERE instead of exiting the whole app
        // (the 114 combo left XashActivity as the only activity: back = home
        // screen + recents re-entry hit a dead GL window).
        private void launchEngine( String gamedir, String extraArgs, boolean disableAmxx, long tapMs ) {
                Intent launch = new Intent( this, su.xash.engine.XashActivity.class )
                        .putExtra( "gamedir", gamedir )
                        .putExtra( "gamelibdir", getApplicationInfo().nativeLibraryDir )
                        // A7 build 126 (item 10): pin the base dir explicitly -
                        // launcher and engine used to derive it independently
                        // and any drift meant the engine looked for valve/ in
                        // another root than the launcher scanned ("no pak in
                        // valve" in game over files the launcher could see)
                        .putExtra( "basedir", getXashDir().getAbsolutePath() )
                        .putExtra( "package", getPackageName() );

                // build 113: argv comes from the Settings tab (the old
                // hard-coded "-dev 2 -log" is just both switches at their
                // default position; turning a switch off drops its flag)
                android.content.SharedPreferences sp = getPreferences( MODE_PRIVATE );
                StringBuilder argv = new StringBuilder();
                if( sp.getBoolean( "engine_dev", true ))
                        argv.append( "-dev 2 " );
                if( sp.getBoolean( "engine_log", true ))
                        argv.append( "-log" );
                if( extraArgs != null && !extraArgs.isEmpty()) {
                        if( argv.length() > 0 ) argv.append( ' ' );
                        argv.append( extraArgs );
                }
                launch.putExtra( "argv", argv.toString().trim() );

                // bypass metamod entirely and load the original server library
                // directly (same gate the engine menu used to send as
                // XASH3D_DISABLE_AMXX) - caller decides per game: CS uses the
                // three switches, HL uses its own addon switch, CZ never
                if( disableAmxx )
                        launch.putExtra( "env", new String[]{ "XASH3D_DISABLE_AMXX", "1" } );

                // A7 build 128 (round 129): stamp the tap - XashActivity reports
                // what tap -> engine activity creation actually cost, so the
                // remaining wait can be named instead of guessed
                if( tapMs > 0 )
                        launch.putExtra( "a7_tap", tapMs );

                // A7 build 128: the tap -> startActivity gap (0 ms is the goal:
                // everything the prep chain used to do here already ran in the
                // warm pass)
                if( tapMs > 0 )
                        Log.i( TAG, "[A7] build128: tap to startActivity "
                                + ( android.os.SystemClock.uptimeMillis() - tapMs )
                                + " ms (game=" + gamedir + ", warm=" + mLaunchPrepDone + ")" );

                startActivity( launch );
                // A7 build 128: no launcher -> engine cross-fade. GoldSource
                // just starts; the default window animation added a visible
                // beat before the GL surface appeared.
                try {
                        overridePendingTransition( 0, 0 );
                } catch( Throwable ignored ) {
                }
                // deliberately NO finish(): back in the game pops to this menu
        }

        // build 115: per-game data guards look for pak*.pak (any number, any
        // case) instead of only pak0.pak, and HL distinguishes "no valve
        // folder" from "valve folder without game paks" - the addon installer
        // auto-creates valve/addons on every launch, so a valve folder that
        // only holds the addon tree used to be reported as a missing game
        // (the real symptom: "valve folder already in xash folder" but no find)
        private boolean dirHasPaks( File dir ) {
                File[] files = dir.listFiles();
                if( files == null ) return false;

                for( File f : files ) {
                        String n = f.getName().toLowerCase();
                        if( f.isFile() && n.startsWith( "pak" ) && n.endsWith( ".pak" ))
                                return true;
                }
                return false;
        }

        // build 116: the folder NAME itself may differ in case from what we
        // ask for (some file managers write "Valve" or "HALFLIFE") - resolve
        // the real entry from the parent listing before giving up on it.
        private File resolveGameDir( String gamedir ) {
                File direct = new File( getXashDir(), gamedir );
                if( direct.isDirectory() )
                        return direct;

                File[] children = getXashDir().listFiles();
                if( children != null )
                        for( File c : children )
                                if( c.isDirectory() && c.getName().equalsIgnoreCase( gamedir ))
                                        return c;
                return null;
        }

        // build 116 (device report: "pak didn't found although the files are
        // there"): send the engine WHERE THE PAKS ARE, not where we assume
        // they live - people often extract a zipped half-life so the real
        // files end up nested (xash/valve/valve/pak0.pak). Breadth-first,
        // depth <= 5, shallowest pak directory wins, path returned RELATIVE
        // to the xash root for the engine (e.g. "valve" or "valve/valve").
        // Returns null when no pak file exists anywhere below the gamedir.
        private String findGamePakDir( String gamedir ) {
                File root = resolveGameDir( gamedir );
                if( root == null )
                        return null;

                String rootPath = getXashDir().getAbsolutePath();
                if( dirHasPaks( root ))
                        return root.getAbsolutePath().substring( rootPath.length() + 1 );

                java.util.ArrayDeque<File> queue = new java.util.ArrayDeque<>();
                java.util.HashSet<String> seen = new java.util.HashSet<>();
                queue.add( root );
                seen.add( root.getAbsolutePath() );

                while( !queue.isEmpty() ) {
                        File dir = queue.poll();
                        File[] children = dir.listFiles();
                        if( children == null ) continue;

                        for( File c : children ) {
                                if( !c.isDirectory() || !seen.add( c.getAbsolutePath()))
                                        continue;
                                if( dirHasPaks( c ))
                                        return c.getAbsolutePath().substring( rootPath.length() + 1 );
                                queue.add( c );
                        }
                }

                // A7 build 126 (item 8, user: "fix no pak in valve try to
                // find"): nothing under the expected folder - look for a
                // SAME-NAMED folder anywhere below the xash root before
                // giving up. A zipped game extract often lands as
                // xash/Half-Life/valve/... or xash/CS/cstrike/... (or with
                // odd casing), which the top-level resolve never saw - the
                // launcher toasted "no pak" while the files sat one folder
                // over. The same-name rule keeps other games' pak folders
                // out of the result.
                java.util.ArrayDeque<File> q2 = new java.util.ArrayDeque<>();
                java.util.HashSet<String> seen2 = new java.util.HashSet<>();
                File xroot = getXashDir();
                q2.add( xroot );
                seen2.add( xroot.getAbsolutePath() );

                while( !q2.isEmpty() ) {
                        File dir = q2.poll();
                        File[] children = dir.listFiles();
                        if( children == null ) continue;

                        for( File c : children ) {
                                if( !c.isDirectory() || !seen2.add( c.getAbsolutePath() ))
                                        continue;
                                if( c.getName().equalsIgnoreCase( gamedir ) && dirHasPaks( c ))
                                        return c.getAbsolutePath().substring( rootPath.length() + 1 );
                                q2.add( c );
                        }
                }
                return null;
        }

        // build 114: Half-Life choice. The HL CLIENT dll ships in this APK
        // under a non-colliding name (libhlclient_android_<arch>.so - the CS
        // client owns libclient_android_<arch>.so), so the engine gets its
        // absolute path via -clientlib; the HL SERVER dll (libhl_android_*)
        // ships under its own name and is found normally.
        private void onHalfLifePressed() {
                if( !hasStorageAccess() ) {
                        requestStorageAccess();
                        return;
                }

                // A7 build 128 (round 129): no prep on this thread - just name
                // what to launch and the tap timestamp.
                beginLaunch( "hl", android.os.SystemClock.uptimeMillis() );
        }

        // A7 build 128: the old onHalfLifePressed() body, unchanged apart
        // from the pak lookup (cachedPakDir instead of the full-tree
        // findGamePakDir walk - that already ran in the warm pass).
        private void startHalfLife( long tapMs ) {
                // build 116: only a MISSING valve folder blocks the start now. The paks
        // are searched anywhere below it (nested folders, wrong-case folder
        // name) and the engine gets the gamedir where they actually are -
        // the old top-level-only check refused to start even when the data
        // sat right there (device report: "pak didn't found ... folder
        // already in xash").
                String pakDir = peekPakDir( "valve" );   // build 142: no I/O at the tap
                if( pakDir == null ) {
                        if( resolveGameDir( "valve" ) == null ) {
                                Toast.makeText( this, R.string.menu_hl_missing,
                                        Toast.LENGTH_LONG ).show();
                                return;
                        }
                        // folder exists but no pak anywhere: still start the
                        // engine (its own log/console explains what's empty)
                        // instead of hard-blocking the launch
                        Toast.makeText( this, R.string.menu_hl_no_paks,
                                Toast.LENGTH_LONG ).show();
                        pakDir = "valve";
                }

                String arch = "arm64";
                if( Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0
                                && "armeabi-v7a".equals( Build.SUPPORTED_ABIS[0] ))
                        arch = "armv7l";

                String clientLib = new File( getApplicationInfo().nativeLibraryDir,
                        "libhlclient_android_" + arch + ".so" ).getAbsolutePath();

                // build 115: HL uses ITS OWN addon switch (enable_hl_amxx) -
                // the CS bot/amxx switches never touch a Half-Life session,
                // so "choosing Half-Life = no ebot/CS addons" holds by design
                android.content.SharedPreferences sp = getPreferences( MODE_PRIVATE );
                boolean hlAmxx = sp.getBoolean( "enable_hl_amxx", true );

                launchEngine( pakDir, "-clientlib " + clientLib, !hlAmxx, tapMs );
        }

        // build 115: Condition Zero joins the chooser inside the same app -
        // same engine binary, gamedir=czero, CS client library. Launched
        // through launchEngine() (NOT the old CZeroActivity trampoline, which
        // still carried FLAG_ACTIVITY_CLEAR_TASK and would recreate the
        // build-114 back-button bug by wiping this launcher off the stack).
        // build 116: user says czero "is same like cs1.6" - so it now uses
        // THE SAME addon switches and gate as Counter-Strike (nested pak
        // discovery + start-anyway rule like Half-Life).
        private void onCZeroPressed() {
                if( !hasStorageAccess() ) {
                        requestStorageAccess();
                        return;
                }

                // A7 build 128 (round 129): prep-free tap, see beginLaunch().
                beginLaunch( "cz", android.os.SystemClock.uptimeMillis() );
        }

        // A7 build 128: the old onCZeroPressed() body, pak lookup cached.
        private void startCZero( long tapMs ) {
                String pakDir = peekPakDir( "czero" );   // build 142: no I/O at the tap
                if( pakDir == null ) {
                        if( resolveGameDir( "czero" ) == null ) {
                                Toast.makeText( this, R.string.menu_cz_missing,
                                        Toast.LENGTH_LONG ).show();
                                return;
                        }
                        Toast.makeText( this, R.string.menu_cz_missing,
                                Toast.LENGTH_LONG ).show();
                        pakDir = "czero";
                }

                launchEngine( pakDir, null,
                        !isAmxxOn() && !isYapbOn() && !isEbotOn(), tapMs );
        }

        // ------------------------------------------------------------------
        // launcher menu (cstrike only): settings + launch, like the engine
        // app's main menu. The switches persist in this app's preferences
        // and take effect through the metamod marker files + plugins.ini,
        // which patchPluginsIni() already honours.
        // ------------------------------------------------------------------

        private void showMenu() {
                setContentView( R.layout.activity_launcher );

                final PillSwitch amxxSwitch = (PillSwitch) findViewById( R.id.menu_enable_amxx );
                final PillSwitch yapbSwitch = (PillSwitch) findViewById( R.id.menu_enable_yapb );
                final PillSwitch ebotSwitch = (PillSwitch) findViewById( R.id.menu_enable_ebot );

                amxxSwitch.setChecked( isAmxxOn() );
                yapbSwitch.setChecked( isYapbOn() );
                ebotSwitch.setChecked( isEbotOn() );

                PillSwitch.OnCheckedChangeListener toggleListener =
                                new PillSwitch.OnCheckedChangeListener() {
                        @Override
                        public void onCheckedChanged( PillSwitch buttonView, boolean isChecked ) {
                                // EBot and YaPB both fill the bot slots, so only
                                // one bot can be active: switching one on turns
                                // the other off (setChecked re-enters this
                                // listener, the nested write is idempotent).
                                if( buttonView == ebotSwitch && isChecked && yapbSwitch.isChecked() )
                                        yapbSwitch.setChecked( false );
                                if( buttonView == yapbSwitch && isChecked && ebotSwitch.isChecked() )
                                        ebotSwitch.setChecked( false );

                                getPreferences( MODE_PRIVATE ).edit()
                                        .putBoolean( "enable_amxx", amxxSwitch.isChecked() )
                                        .putBoolean( "enable_yapb_bots", yapbSwitch.isChecked() )
                                        .putBoolean( "enable_ebot_bots", ebotSwitch.isChecked() )
                                        .apply();
                                writeMetaMarker( "amxmodx.disabled", !amxxSwitch.isChecked() );
                                writeMetaMarker( "yapb.disabled", !yapbSwitch.isChecked() );
                                writeMetaMarker( "ebot.disabled", !ebotSwitch.isChecked() );
                                writeMetaMarker( "ebot.enabled", ebotSwitch.isChecked() );
                                patchPluginsIni();
                                refreshMenuStatus();
                        }
                };
                amxxSwitch.setOnCheckedChangeListener( toggleListener );
                yapbSwitch.setOnCheckedChangeListener( toggleListener );
                ebotSwitch.setOnCheckedChangeListener( toggleListener );

                ((android.view.View) findViewById( R.id.row_amxx )).setOnClickListener(
                                new android.view.View.OnClickListener() {
                        @Override
                        public void onClick( android.view.View v ) {
                                amxxSwitch.toggle();
                        }
                });
                ((android.view.View) findViewById( R.id.row_yapb )).setOnClickListener(
                                new android.view.View.OnClickListener() {
                        @Override
                        public void onClick( android.view.View v ) {
                                yapbSwitch.toggle();
                        }
                });
                ((android.view.View) findViewById( R.id.row_ebot )).setOnClickListener(
                                new android.view.View.OnClickListener() {
                        @Override
                        public void onClick( android.view.View v ) {
                                ebotSwitch.toggle();
                        }
                });

                // build 116 launcher rework: ONE play button driven by the CHOOSE GAME
                // tiles at the top. The pick (persisted under selected_game)
                // decides the visible options block, the button label and the
                // launch target. CS and CZ share the CS switches (user:
                // "czero is same like cs1.6"); HL keeps its own bundle row.
                final Button playButton = (Button) findViewById( R.id.menu_launch );
                final TextView detailTitle = (TextView) findViewById( R.id.game_detail_title );
                final TextView detailDesc = (TextView) findViewById( R.id.game_detail_desc );
                final TextView detailPath = (TextView) findViewById( R.id.game_detail_path );
                final android.view.View optionsCs = findViewById( R.id.options_cs );
                final android.view.View optionsHl = findViewById( R.id.options_hl );
                final android.view.View pickCs = findViewById( R.id.game_pick_cs );
                final android.view.View pickHl = findViewById( R.id.game_pick_hl );
                final android.view.View pickCz = findViewById( R.id.game_pick_cz );

                final int[] selectedGame = { getPreferences( MODE_PRIVATE )
                        .getInt( "selected_game", 0 ) };
                if( selectedGame[0] < 0 || selectedGame[0] > 2 )
                        selectedGame[0] = 0;

                final Runnable applySelection = new Runnable() {
                        @Override
                        public void run() {
                                int g = selectedGame[0];

                                pickCs.setBackgroundResource(
                                        g == 0 ? R.drawable.tile_selected : R.drawable.tile_idle );
                                ((Button) pickCs).setTextColor(
                                        g == 0 ? 0xFFFFFFFF : 0xFFAEB7C6 );
                                pickHl.setBackgroundResource(
                                        g == 1 ? R.drawable.tile_selected : R.drawable.tile_idle );
                                ((Button) pickHl).setTextColor(
                                        g == 1 ? 0xFFFFFFFF : 0xFFAEB7C6 );
                                pickCz.setBackgroundResource(
                                        g == 2 ? R.drawable.tile_selected : R.drawable.tile_idle );
                                ((Button) pickCz).setTextColor(
                                        g == 2 ? 0xFFFFFFFF : 0xFFAEB7C6 );

                                detailTitle.setText( new int[] {
                                        R.string.game_card_cs, R.string.game_card_hl,
                                        R.string.game_card_cz }[g] );
                                detailDesc.setText( new int[] {
                                        R.string.game_desc_cs, R.string.game_desc_hl,
                                        R.string.game_desc_cz }[g] );
                                detailPath.setText( "/sdcard/xash/" + new String[] {
                                        "cstrike", "valve", "czero" }[g] );

                                optionsCs.setVisibility( g == 1 ?
                                        android.view.View.GONE : android.view.View.VISIBLE );
                                optionsHl.setVisibility( g == 1 ?
                                        android.view.View.VISIBLE : android.view.View.GONE );

                                playButton.setText( new int[] {
                                        R.string.menu_launch, R.string.menu_launch_hl,
                                        R.string.menu_launch_cz }[g] );
                        }
                };

                final android.content.SharedPreferences gamePrefs =
                        getPreferences( MODE_PRIVATE );
                final int[] clickers = { R.id.game_pick_cs, R.id.game_pick_hl,
                                         R.id.game_pick_cz };
                for( int gi = 0; gi < clickers.length; gi++ ) {
                        final int idx = gi;
                        findViewById( clickers[gi] ).setOnClickListener(
                                        new android.view.View.OnClickListener() {
                                @Override
                                public void onClick( android.view.View v ) {
                                        selectedGame[0] = idx;
                                        gamePrefs.edit()
                                                .putInt( "selected_game", idx ).apply();
                                        applySelection.run();
                                }
                        });
                }
                applySelection.run();

                playButton.setOnClickListener(
                                new android.view.View.OnClickListener() {
                        @Override
                        public void onClick( android.view.View v ) {
                                switch( selectedGame[0] ) {
                                case 1:  onHalfLifePressed();  break;
                                case 2:  onCZeroPressed();     break;
                                default: onLaunchPressed();
                                }
                        }
                });

                // build 115: the Half-Life addon switch (persisted as
                // enable_hl_amxx, read in onHalfLifePressed - it never
                // touches the CS amxx/yapb/ebot switches)
                final PillSwitch hlAmxxSwitch = (PillSwitch)
                        findViewById( R.id.menu_enable_hl_amxx );
                if( hlAmxxSwitch != null ) {
                        hlAmxxSwitch.setChecked(
                                getPreferences( MODE_PRIVATE )
                                        .getBoolean( "enable_hl_amxx", true ));
                        hlAmxxSwitch.setOnCheckedChangeListener(
                                        new PillSwitch.OnCheckedChangeListener() {
                                @Override
                                public void onCheckedChanged( PillSwitch buttonView,
                                                              boolean checked ) {
                                        getPreferences( MODE_PRIVATE ).edit()
                                                .putBoolean( "enable_hl_amxx", checked )
                                                .apply();
                                }
                        });
                        android.view.View hlRow = findViewById( R.id.row_hl_amxx );
                        if( hlRow != null )
                                hlRow.setOnClickListener(
                                                new android.view.View.OnClickListener() {
                                        @Override
                                        public void onClick( android.view.View v ) {
                                                hlAmxxSwitch.toggle();
                                        }
                                });
                }

                // shared bottom tab bar: Launch tab is inert here (the
                // active tab does nothing on tap), the other tabs
                // open their screens.
                TabBar.init( this, TabBar.TAB_LAUNCH );

                ((android.view.View) findViewById( R.id.menu_reinstall )).setOnClickListener(
                                new android.view.View.OnClickListener() {
                        @Override
                        public void onClick( android.view.View v ) {
                                if( !hasStorageAccess() ) {
                                        requestStorageAccess();
                                        return;
                                }

                                // true reinstall: drop both stamps and let the
                                // extraction reset every BUNDLED file to stock.
                                // The user's own addons are not part of the
                                // bundle and are never touched (waypoints stay
                                // protected too - see mayOverwrite).
                                getPreferences( MODE_PRIVATE ).edit()
                                        .remove( "addons_version" )
                                        .remove( "bot_assets_version" ).apply();
                                try {
                                        extractAddons( true );
                                } catch( Throwable t ) {
                                        Log.w( TAG, "addons re-install failed", t );
                                        Toast.makeText( MainActivity.this,
                                                "Addons installation error: " + t.getMessage(),
                                                Toast.LENGTH_LONG ).show();
                                        logInstallError( "cstrike addons reinstall (cs16client)", t );
                                }
                                patchPluginsIni();
                                // reinstall never deletes vault files, but a
                                // fresh tree (or a pack installed afterwards)
                                // still needs its seeds back
                                ensureZpxVaultSeeds();
                                refreshMenuStatus();
                                Toast.makeText( MainActivity.this,
                                        R.string.menu_reinstalled, Toast.LENGTH_SHORT ).show();
                        }
                });

                try {
                        String ver = getPackageManager()
                                .getPackageInfo( getPackageName(), 0 ).versionName;
                        ((TextView) findViewById( R.id.menu_footer )).setText(
                                "CS16Client  v" + ver );
                } catch( Throwable ignored ) {
                }

                // storage banner: tapping re-opens the permission settings page
                android.view.View storageBanner = findViewById( R.id.storage_banner );
                if( storageBanner != null ) {
                        storageBanner.setOnClickListener(
                                        new android.view.View.OnClickListener() {
                                @Override
                                public void onClick( android.view.View v ) {
                                        requestStorageAccess();
                                }
                        });
                }
                updateStorageBanner();

                refreshMenuStatus();
        }

        private void onLaunchPressed() {
                if( !hasStorageAccess() ) {
                        // can't install or run without storage - route to settings
                        requestStorageAccess();
                        return;
                }

                // A7 build 128 (round 129, user: "fix launcher it too late to
                // start game"): this used to run extractAddonsIfNeeded(),
                // patchPluginsIni(), ensureZpxVaultSeeds(),
                // ModuleInstaller.reapplyAll() AND the depth-5 pak search
                // right here on the UI thread before startActivity() - every
                // one of them over Android FUSE - so the tap froze the menu
                // first. All of it now runs on the install thread above
                // (warmLaunchCache()) while the menu comes up; a tap is just
                // "go".
                beginLaunch( "cs", android.os.SystemClock.uptimeMillis() );
        }

        private void refreshMenuStatus() {
                TextView engineStatus = (TextView) findViewById( R.id.menu_status_engine );
                TextView addonsStatus = (TextView) findViewById( R.id.menu_status_addons );
                android.widget.ImageView engineIcon = (android.widget.ImageView)
                        findViewById( R.id.status_engine_icon );
                android.widget.ImageView addonsIcon = (android.widget.ImageView)
                        findViewById( R.id.status_addons_icon );
                if( engineStatus == null || addonsStatus == null )
                        return;

                boolean engineOk = enginePresent();
                engineStatus.setText( engineOk
                        ? getString( R.string.status_installed )
                        : getString( R.string.status_missing ));
                engineStatus.setTextColor( engineOk ? 0xFF8B93A3 : 0xFFFF6B61 );
                if( engineIcon != null )
                        engineIcon.setImageResource( engineOk
                                ? R.drawable.ic_check : R.drawable.ic_warn );

                // A7 build 140 (round 140 item 4, user: "addons bundle said
                // will install on launch but i already did"): the marker was
                // the arm64 libmetamod .so - since build 137 NO native
                // library is written to the gamedir any more, so this check
                // could never pass and the card showed the orange "Will
                // install on launch" forever no matter how installed the
                // bundle was. plugins.ini is the bundle's own ABI-neutral
                // marker: the file extractZipIfNeeded gates on and
                // patchPluginsIni() keeps in sync on every launch.
                File marker = new File( getXashDir(),
                        "cstrike/addons/metamod/plugins.ini" );
                boolean addonsOk = marker.isFile();
                addonsStatus.setText( addonsOk
                        ? getString( R.string.status_installed )
                        : getString( R.string.status_will_install ));
                addonsStatus.setTextColor( addonsOk ? 0xFF8B93A3 : 0xFFFFC25E );
                if( addonsIcon != null )
                        addonsIcon.setImageResource( addonsOk
                                ? R.drawable.ic_check : R.drawable.ic_warn );
        }

        private boolean enginePresent() {
                // build 114 merge: the engine is compiled INTO this app
                // (su.xash.engine.XashActivity + libxash/libSDL2 in this APK),
                // so there is no separate engine package to look up anymore.
                return true;
        }

        /**
         * Bot packs (YaPB conf/graphs, EBot configs) ship as individual files
         * under assets/addons/**, but both bots read them with plain libc
         * fopen() against the game directory - which cannot see inside the
         * APK. Copy them to /xash/cstrike/addons once per BOT_ASSETS_VERSION so
         * every bot file is on disk next to the metamod plugins.
         */
        private int extractBotAssetsIfNeeded( boolean full, boolean factoryReset,
                Map<String, String> manifest ) {
                SharedPreferences prefs = getPreferences( MODE_PRIVATE );
                File base = new File( getXashDir(), "cstrike/addons" );

                // the version stamp alone is not enough: if anything removes
                // the bot files from the game folder (a cleanup app, a manual
                // delete, a broken install) the old check would consider the
                // assets "done" forever and never restore them - verify the
                // key files are really on disk before skipping
                boolean stamped = BOT_ASSETS_VERSION.equals(
                        prefs.getString( "bot_assets_version", "" ) );
                boolean present = new File( base, "ebot/ebot.cfg" ).isFile()
                        && new File( base, "ebot/names.cfg" ).isFile()
                        && new File( base, "ebot/avatars.cfg" ).isFile()
                        && new File( base, "ebot/waypoints" ).isDirectory()
                        && new File( base, "yapb/conf/yapb.cfg" ).isFile()
                        && new File( getXashDir(), "cstrike/BotProfile.db" ).isFile();
                if( !full && stamped && present )
                        return 0;
                if( stamped )
                        Log.w( TAG, "bot assets incomplete on disk - re-extracting" );
                int count = 0;
                count += copyAssetTree( "addons/yapb", new File( base, "yapb" ),
                        false, manifest, full, factoryReset );
                count += copyAssetTree( "addons/ebot", new File( base, "ebot" ),
                        false, manifest, full, factoryReset );
                // bundled waypoints never overwrite existing .ewp files -
                // analyzed/user-provided waypoint files win over shipped ones
                // (on the reinstall path too - this rule is absolute)
                count += copyAssetTree( "addons/ebot/waypoints",
                        new File( base, "ebot/waypoints" ), true, manifest, full,
                        factoryReset );
                // keep the (bot-created) waypoints directory around
                new File( base, "ebot/waypoints" ).mkdirs();
                // ReGameDLL's built-in bot code reads these two databases
                // from the gamedir root (bot_profile_db cvar / BotChatter
                // phrase init) - they ship as APK assets but nothing used
                // to copy them to disk
                count += copyAssetTree( "BotProfile.db",
                        new File( getXashDir(), "cstrike/BotProfile.db" ),
                        false, manifest, full, factoryReset );
                count += copyAssetTree( "BotChatter.db",
                        new File( getXashDir(), "cstrike/BotChatter.db" ),
                        false, manifest, full, factoryReset );

                prefs.edit().putString( "bot_assets_version", BOT_ASSETS_VERSION ).apply();
                Log.i( TAG, "bot assets extracted: " + count + " files -> "
                        + base.getAbsolutePath() );
                return count;
        }

        private int copyAssetTree( String assetPath, File dest, boolean skipExisting,
                Map<String, String> manifest, boolean full, boolean factoryReset ) {
                int count = 0;
                try {
                        String[] children = getAssets().list( assetPath );
                        if( children == null || children.length == 0 ) {
                                // leaf = file (an empty directory marker lists
                                // empty too - then open() fails and we mkdir)
                                if( dest.exists() ) {
                                        // bundled waypoints never overwrite
                                        // existing .ewp files - analyzed /
                                        // user-provided waypoint files win
                                        if( skipExisting )
                                                return 0;
                                        // user-owned or user-edited file:
                                        // keep the disk copy
                                        if( !mayOverwrite( dest, relToXash( dest ),
                                                        manifest, full, factoryReset ))
                                                return 0;
                                }
                                InputStream in;
                                try {
                                        in = getAssets().open( assetPath );
                                } catch( Throwable notAFile ) {
                                        dest.mkdirs();
                                        return 0;
                                }
                                dest.getParentFile().mkdirs();
                                FileOutputStream out = new FileOutputStream( dest );
                                byte[] buf = new byte[16384];
                                int n;
                                while(( n = in.read( buf )) > 0 )
                                        out.write( buf, 0, n );
                                out.close();
                                in.close();
                                // record what we wrote (see mayOverwrite)
                                manifest.put( relToXash( dest ), sha256Of( dest ));
                                return 1;
                        }

                        dest.mkdirs();
                        for( String child : children ) {
                                // the ebot tree's waypoints/ subdir is synced
                                // separately with the never-overwrite policy
                                if( assetPath.equals( "addons/ebot" ) && child.equals( "waypoints" ) )
                                        continue;
                                count += copyAssetTree( assetPath + "/" + child,
                                        new File( dest, child ), skipExisting,
                                        manifest, full, factoryReset );
                        }
                } catch( Throwable t ) {
                        Log.w( TAG, "asset copy failed: " + assetPath, t );
                }
                return count;
        }

        /** path of f relative to the xash dir, '/'-separated (manifest key). */
        private String relToXash( File f ) {
                try {
                        String base = getXashDir().getCanonicalPath() + File.separator;
                        String full = f.getCanonicalPath();
                        if( full.startsWith( base ))
                                return full.substring( base.length() )
                                        .replace( File.separatorChar, '/' );
                } catch( Throwable ignored ) {
                }
                return f.getPath().replace( File.separatorChar, '/' );
        }

        private boolean isAmxxOn() {
                return getPreferences( MODE_PRIVATE ).getBoolean( "enable_amxx", true );
        }

        // ------------------------------------------------------------------
        // fvault seed files for the Zombie Plague X pack
        //
        // fvault.inc opens its vaults READ-FIRST and calls feof() on the
        // fopen() result without checking it: when the vault file is
        // missing, every rank/points/packs save dies with "Invalid file
        // handle 0 used in native feof" and the plugin gets paused. fvault
        // only mkdirs from its listing stocks, so a fresh install can never
        // heal itself.
        //
        // These vaults belong to the Zombie Plague X pack alone, so the
        // empty seeds are created here - and only while that pack is
        // actually installed and enabled. Installs that never run it (other
        // zombie mods, plain CS) never get stray vault files, and valve
        // (Half-Life) never gets them at all. Existing vault files are
        // player progress: created only when missing, never overwritten or
        // removed (see mayOverwrite).
        // ------------------------------------------------------------------

        private void ensureZpxVaultSeeds() {
                try {
                        // the pack's core plugin - Zombie Plague Advance
                        // ships a differently named one
                        // (zombie_plague_advance_*), so it can never match
                        File core = new File( getXashDir(),
                                "cstrike/addons/amxmodx/plugins/zombie_plague.amxx" );
                        if( !core.isFile() )
                                return;
                        if( !isAmxxOn() || !zpxPackListed() )
                                return;

                        File vault = new File( getXashDir(),
                                "cstrike/addons/amxmodx/data/file_vault" );
                        if( !vault.isDirectory() && !vault.mkdirs() )
                                return;

                        // vault names recovered from the pack's plugin
                        // binaries (each plugin passes its own name to
                        // fvault); Points/points_db/status_db are the names
                        // an older revision of that pack wrote
                        String[] names = {
                                "zpx_rank_system", "zpx_quests_system",
                                "zpx_save_points", "zpx_save_packs",
                                "zpx_save_zclass", "zpx_save_knife",
                                "zpx_save_get", "Points", "points_db",
                                "status_db" };
                        for( String n : names ) {
                                File f = new File( vault, n + ".txt" );
                                if( !f.isFile() )
                                        f.createNewFile();
                        }
                } catch( Throwable t ) {
                        Log.w( TAG, "zpx vault seeds failed", t );
                }
        }

        /**
         * true when zombie_plague.amxx is enabled in one of the AMXX
         * plugin lists (configs/plugins.ini + every configs/plugins-*.ini,
         * which the module loads on top of it - see
         * LoadExtraPluginsFromDir in meta_api.cpp). A commented-out line
         * ("; zombie_plague.amxx") does not count.
         */
        private boolean zpxPackListed() {
                File configs = new File( getXashDir(),
                        "cstrike/addons/amxmodx/configs" );
                File[] inis = configs.listFiles();
                if( inis == null )
                        return false;
                for( File f : inis ) {
                        String name = f.getName().toLowerCase( Locale.US );
                        if( !f.isFile() || !( name.equals( "plugins.ini" )
                                || ( name.startsWith( "plugins-" )
                                        && name.endsWith( ".ini" ))))
                                continue;
                        try {
                                BufferedReader r = new BufferedReader(
                                        new FileReader( f ));
                                String line;
                                while(( line = r.readLine()) != null ) {
                                        int c = line.indexOf( ';' );
                                        if( c >= 0 )
                                                line = line.substring( 0, c );
                                        if( line.trim().equalsIgnoreCase(
                                                "zombie_plague.amxx" )) {
                                                r.close();
                                                return true;
                                        }
                                }
                                r.close();
                        } catch( Throwable ignored ) {
                        }
                }
                return false;
        }

        /**
         * A reinstall wipes this app's SharedPreferences but the marker files
         * in /xash survive - so the switches must be re-seeded from disk when
         * the prefs are fresh, otherwise the menu would show ON while the
         * engine actually loads with AMXX/YaPB disabled.
         */
        private void syncSwitchPrefsWithMarkers() {
                SharedPreferences prefs = getPreferences( MODE_PRIVATE );
                if( prefs.contains( "enable_amxx" ) && prefs.contains( "enable_yapb_bots" )
                        && prefs.contains( "enable_ebot_bots" ) )
                        return;

                File metaDir = new File( getXashDir(), "cstrike/addons/metamod" );
                boolean amxx = !new File( metaDir, "amxmodx.disabled" ).isFile();
                boolean yapb = !new File( metaDir, "yapb.disabled" ).isFile();

                // EBot is opt-in: with neither marker file present (first run
                // after the update) it starts DISABLED so it can never silently
                // replace YaPB; from then on the two marker files are the
                // on-disk truth and they survive app reinstalls.
                File ebotMarker = new File( metaDir, "ebot.disabled" );
                if( !ebotMarker.isFile() && !new File( metaDir, "ebot.enabled" ).isFile() )
                        writeMetaMarker( "ebot.disabled", true );
                boolean ebot = !ebotMarker.isFile();

                prefs.edit()
                        .putBoolean( "enable_amxx", amxx )
                        .putBoolean( "enable_yapb_bots", yapb )
                        .putBoolean( "enable_ebot_bots", ebot )
                        .apply();
                Log.i( TAG, "switch prefs seeded from markers: amxx=" + amxx
                        + ", yapb=" + yapb + ", ebot=" + ebot );
        }

        private boolean isYapbOn() {
                return getPreferences( MODE_PRIVATE ).getBoolean( "enable_yapb_bots", true );
        }

        private boolean isEbotOn() {
                return getPreferences( MODE_PRIVATE ).getBoolean( "enable_ebot_bots", false );
        }

        private void writeMetaMarker( String name, boolean present ) {
                try {
                        File metaDir = new File( getXashDir(), "cstrike/addons/metamod" );
                        metaDir.mkdirs();
                        File marker = new File( metaDir, name );
                        if( present )
                                marker.createNewFile();
                        else
                                marker.delete();
                } catch( Throwable t ) {
                        Log.w( TAG, "marker write failed: " + name, t );
                }
        }
}
