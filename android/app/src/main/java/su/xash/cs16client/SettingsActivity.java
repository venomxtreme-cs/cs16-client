package su.xash.cs16client;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

import su.xash.engine.SteamIdConnect;
import su.xash.engine.SteamOpenId;

/**
 * Settings tab (build 113: "add settings that in xash crash log and
 * more"): engine launch options (-log / -dev, persisted and read by
 * MainActivity.launchEngine), the engine crash-log viewer entry and
 * the about block.
 */
public class SettingsActivity extends Activity {
	private TextView logStatus;

	@Override
	protected void onCreate( Bundle savedInstanceState ) {
		super.onCreate( savedInstanceState );
		setContentView( R.layout.activity_settings );

		// shared bottom tab bar: Settings tab is inert here
		TabBar.init( this, TabBar.TAB_SETTINGS );

		final SharedPreferences prefs = getPreferences( MODE_PRIVATE );

		final PillSwitch logSwitch = (PillSwitch) findViewById( R.id.settings_opt_log );
		final PillSwitch devSwitch = (PillSwitch) findViewById( R.id.settings_opt_dev );
		logSwitch.setChecked( prefs.getBoolean( "engine_log", true ));
		devSwitch.setChecked( prefs.getBoolean( "engine_dev", true ));

		PillSwitch.OnCheckedChangeListener listener = new PillSwitch.OnCheckedChangeListener() {
			@Override
			public void onCheckedChanged( PillSwitch buttonView, boolean isChecked ) {
				prefs.edit()
					.putBoolean( "engine_log", logSwitch.isChecked())
					.putBoolean( "engine_dev", devSwitch.isChecked())
					.apply();
			}
		};
		logSwitch.setOnCheckedChangeListener( listener );
		devSwitch.setOnCheckedChangeListener( listener );

		logStatus = (TextView) findViewById( R.id.settings_log_status );

		findViewById( R.id.settings_log_open ).setOnClickListener(
			new View.OnClickListener() {
				@Override
				public void onClick( View v ) {
					startActivity( new Intent( SettingsActivity.this, LogViewerActivity.class ));
				}
			});

		findViewById( R.id.settings_log_share ).setOnClickListener(
			new View.OnClickListener() {
				@Override
				public void onClick( View v ) {
					if( !EngineLog.share( SettingsActivity.this ))
						Toast.makeText( SettingsActivity.this,
							R.string.logviewer_nothing, Toast.LENGTH_SHORT ).show();
				}
			});

		findViewById( R.id.settings_log_clear ).setOnClickListener(
			new View.OnClickListener() {
				@Override
				public void onClick( View v ) {
					if( EngineLog.clear( SettingsActivity.this ))
						Toast.makeText( SettingsActivity.this,
							R.string.settings_log_cleared, Toast.LENGTH_SHORT ).show();
					refreshLogStatus();
				}
			});

		bindSteamViews();
		refreshLogStatus();
		refreshAbout();
		refreshSteam();
	}

	@Override
	protected void onResume() {
		super.onResume();
		// A7 build 136 (item 1): coming back from the Steam sign-in screen
		// must show the new state right away (signed in / signed out)
		refreshSteam();
	}

	// ------------------------------------------------------------------
	// A7 build 136 (item 1): the Steam account card. Reads the same file
	// the sign-in screen writes ( <basedir>/<gamedir>/friends/steamid.txt )
	// and shows "Steam ID: ..." with the Steam logo; tapping the card or
	// the button opens su.xash.engine.SteamFriendsActivity (the WebView
	// login/friends screen) with the pinned base dir + gamedir, exactly
	// like the engine's "steamfriends" command does in-game.
	// ------------------------------------------------------------------
	private TextView mSteamIdLine;

	private void refreshSteam() {
		mSteamIdLine = (TextView) findViewById( R.id.settings_steam_id );
		TextView title = (TextView) findViewById( R.id.settings_steam_title );

		String id = readSteamId();

		if( id.length() > 0 ) {
			title.setText( R.string.settings_steam_signed );
			mSteamIdLine.setText( "Steam ID: " + id );
		} else {
			title.setText( R.string.settings_steam_not_signed );
			mSteamIdLine.setText( R.string.settings_steam_hint );
		}

		android.util.Log.i( "Settings",
			"[A7] build136: settings steam card " + ( id.length() > 0 ? ("id " + id) : "not signed in" ));
	}

	private View.OnClickListener steamOpenListener = new View.OnClickListener() {
		@Override
		public void onClick( View v ) {
			openSteamScreen();
		}
	};

	// wired from onCreate after the views exist (the card + the button
	// both open the same screen, and the listener hooks are attached in
	// onCreate so the layout ids are guaranteed present)
	void bindSteamViews() {
		findViewById( R.id.settings_steam_card ).setOnClickListener( steamOpenListener );
		findViewById( R.id.settings_steam_open ).setOnClickListener( steamOpenListener );

		// A7 build 146 (round 146 item 2, user: connect from the LAUNCHER
		// "without browser"): inline ID row + Connect button run the
		// WebViewless su.xash.engine.SteamIdConnect helper - writes the
		// same friends/steamid.txt + name/photo files the sign-in screen
		// and the friends screen do, no Chrome hand-off, no API key
		steamIdInput = (EditText) findViewById( R.id.settings_steam_id_input );
		steamStatus = (TextView) findViewById( R.id.settings_steam_status );
		findViewById( R.id.settings_steam_connect ).setOnClickListener(
			new View.OnClickListener() {
				@Override
				public void onClick( View v ) {
					connectBySteamId();
				}
			});

		// A7 build 147 (round 147 item 5, user: "Steam OpenID sign-in as
		// anti-steal proof", BOTH PC and Android): browser -> 127.0.0.1
		// redirect catch -> keyless check_authentication verification
		// (NO Steam Web API key), then the same connect-by-id write path
		findViewById( R.id.settings_steam_openid ).setOnClickListener(
			new View.OnClickListener() {
				@Override
				public void onClick( View v ) {
					signInWithSteam();
				}
			});
	}

	private EditText steamIdInput;
	private TextView steamStatus;

	// <basedir>/<gamedir> for the game currently picked in the launcher
	private File gameRoot() {
		String gamedir = currentGameDir();
		File root;
		try {
			root = android.os.Environment.getExternalStorageDirectory();
			if( root == null )
				throw new IllegalStateException();
		} catch( Throwable t ) {
			root = new File( "/storage/emulated/0" );
		}
		return new File( new File( root, "xash" ), gamedir );
	}

	// 0 = cstrike, 1 = valve, 2 = czero (stored by MainActivity.launchEngine)
	private String currentGameDir() {
		int game = getSharedPreferences( "MainActivity", MODE_PRIVATE )
			.getInt( "selected_game", 0 );
		return game == 1 ? "valve" : ( game == 2 ? "czero" : "cstrike" );
	}

	private void connectBySteamId() {
		String raw = steamIdInput.getText() != null ? steamIdInput.getText().toString() : "";
		if( raw.trim().isEmpty()) {
			showSteamStatus( "Type the Steam ID of the account first" );
			return;
		}

		steamIdInput.setEnabled( false );
		showSteamStatus( "Connecting ..." );

		SteamIdConnect.connect( gameRoot(), raw, new SteamIdConnect.Listener() {
			@Override
			public void onStatus( String status ) {
				showSteamStatus( status );
			}

			@Override
			public void onConnected( String steamId64, String persona, boolean changed ) {
				refreshSteam(); // card title + ID line pick the new id up
				if( changed )
					steamIdInput.setText( "" );
			}

			@Override
			public void onDone( boolean ok ) {
				steamIdInput.setEnabled( true ); // failed lookup unblocks too
			}
		});
	}

	private void showSteamStatus( String text ) {
		if( steamStatus == null )
			return;
		steamStatus.setText( text != null ? text : "" );
		steamStatus.setVisibility( text != null && !text.isEmpty() ? View.VISIBLE : View.GONE );
	}

	// round 147 item 5: OpenID proof sign-in - the browser proves the
	// account ownership, the local listener verifies it keylessly and the
	// verified id lands in the SAME connect-by-id write path (account
	// files + identity vault merge from round 147 item 6)
	private void signInWithSteam() {
		steamIdInput.setEnabled( false );
		showSteamStatus( "Opening Steam sign-in in your browser ..." );

		SteamOpenId.start( this, gameRoot(), new SteamIdConnect.Listener() {
			@Override
			public void onStatus( String status ) {
				showSteamStatus( status );
			}

			@Override
			public void onConnected( String steamId64, String persona, boolean changed ) {
				refreshSteam(); // card title + ID line pick the new id up
				if( changed )
					steamIdInput.setText( "" );
			}

			@Override
			public void onDone( boolean ok ) {
				steamIdInput.setEnabled( true ); // failed proof unblocks too
			}
		});
	}

	private void openSteamScreen() {
		try {
			int game = getSharedPreferences( "MainActivity", MODE_PRIVATE )
				.getInt( "selected_game", 0 );
			String gamedir = game == 1 ? "valve" : ( game == 2 ? "czero" : "cstrike" );

			File root;
			try {
				root = android.os.Environment.getExternalStorageDirectory();
				if( root == null )
					throw new IllegalStateException();
			} catch( Throwable t ) {
				root = new File( "/storage/emulated/0" );
			}
			File xash = new File( root, "xash" );

			Intent i = new Intent( this, su.xash.engine.SteamFriendsActivity.class )
				.putExtra( "basedir", xash.getAbsolutePath() )
				.putExtra( "gamedir", gamedir );
			startActivity( i );
		} catch( Throwable t ) {
			// the sign-in screen was missing from this app's manifest before
			// build 136 - report instead of dying silently
			android.util.Log.e( "Settings",
				"[A7] build136: steam screen failed to open: " + t );
			Toast.makeText( this, "Steam sign-in screen unavailable", Toast.LENGTH_SHORT ).show();
		}
	}

	// friends/steamid.txt under the currently selected game dir, trimmed
	private String readSteamId() {
		try {
			int game = getSharedPreferences( "MainActivity", MODE_PRIVATE )
				.getInt( "selected_game", 0 );
			String gamedir = game == 1 ? "valve" : ( game == 2 ? "czero" : "cstrike" );

			File xash;
			try {
				xash = new File( android.os.Environment.getExternalStorageDirectory(), "xash" );
			} catch( Throwable t ) {
				xash = new File( "/storage/emulated/0/xash" );
			}

			File f = new File( new File( xash, gamedir ), "friends/steamid.txt" );
			if( !f.isFile())
				return "";

			// plain stream read: works on every API level this app supports
			java.io.FileInputStream in = new java.io.FileInputStream( f );
			try {
				java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
				byte[] chunk = new byte[256];
				int n;
				while(( n = in.read( chunk )) > 0 )
					out.write( chunk, 0, n );
				return new String( out.toByteArray(), "UTF-8" ).trim();
			} finally {
				in.close();
			}
		} catch( Throwable t ) {
			return "";
		}
	}

	private void refreshLogStatus() {
		File f = EngineLog.find( this );
		if( f == null )
			logStatus.setText( R.string.settings_log_missing );
		else
			logStatus.setText( EngineLog.describe( f ));
	}

	private void refreshAbout() {
		TextView version = (TextView) findViewById( R.id.settings_version );
		TextView engine = (TextView) findViewById( R.id.settings_engine_status );

		try {
			PackageInfo pi = getPackageManager().getPackageInfo( getPackageName(), 0 );
			version.setText( "CS16Fresh  v" + pi.versionName );
		} catch( Throwable t ) {
			version.setText( "CS16Fresh" );
		}

		// build 114 merge: the engine is compiled into this APK - there is no
		// separate engine package to report anymore
		engine.setText( R.string.settings_engine_missing );
	}
}
