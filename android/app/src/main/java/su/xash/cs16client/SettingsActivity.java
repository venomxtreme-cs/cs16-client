package su.xash.cs16client;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

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

		refreshLogStatus();
		refreshAbout();
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
