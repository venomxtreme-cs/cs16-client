package su.xash.cs16client;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

/**
 * Full-screen engine.log reader (build 113): monospace tail with
 * copy / share / clear / refresh, opened from the Settings tab. Same
 * interaction rules as the compiler log - no text selection, so
 * manual scrolling never freezes.
 */
public class LogViewerActivity extends Activity {
	private TextView textView;
	private ScrollView scroll;

	@Override
	protected void onCreate( Bundle savedInstanceState ) {
		super.onCreate( savedInstanceState );
		setContentView( R.layout.activity_logviewer );

		textView = (TextView) findViewById( R.id.logviewer_text );
		textView.setTypeface( Typeface.MONOSPACE );
		scroll = (ScrollView) findViewById( R.id.logviewer_scroll );

		findViewById( R.id.logviewer_back ).setOnClickListener(
			new View.OnClickListener() {
				@Override
				public void onClick( View v ) {
					finish();
				}
			});

		findViewById( R.id.logviewer_copy ).setOnClickListener(
			new View.OnClickListener() {
				@Override
				public void onClick( View v ) {
					EngineLog.copyToClipboard( LogViewerActivity.this,
						textView.getText().toString());
					Toast.makeText( LogViewerActivity.this,
						R.string.logviewer_copied, Toast.LENGTH_SHORT ).show();
				}
			});

		findViewById( R.id.logviewer_share ).setOnClickListener(
			new View.OnClickListener() {
				@Override
				public void onClick( View v ) {
					if( !EngineLog.share( LogViewerActivity.this ))
						Toast.makeText( LogViewerActivity.this,
							R.string.logviewer_nothing, Toast.LENGTH_SHORT ).show();
				}
			});

		findViewById( R.id.logviewer_clear ).setOnClickListener(
			new View.OnClickListener() {
				@Override
				public void onClick( View v ) {
					if( EngineLog.clear( LogViewerActivity.this ))
						Toast.makeText( LogViewerActivity.this,
							R.string.settings_log_cleared, Toast.LENGTH_SHORT ).show();
					reload();
				}
			});

		findViewById( R.id.logviewer_refresh ).setOnClickListener(
			new View.OnClickListener() {
				@Override
				public void onClick( View v ) {
					reload();
				}
			});

		reload();
	}

	private void reload() {
		TextView path = (TextView) findViewById( R.id.logviewer_path );
		File f = EngineLog.find( this );

		if( f == null ) {
			path.setText( "" );
			textView.setText( R.string.settings_log_missing );
			return;
		}

		path.setText( EngineLog.describe( f ));

		String text = EngineLog.readTail( f );
		textView.setText( text.length() == 0
			? getString( R.string.logviewer_empty ) : text );

		// land on the newest lines at the bottom
		scroll.post( new Runnable() {
			@Override
			public void run() {
				scroll.fullScroll( View.FOCUS_DOWN );
			}
		});
	}
}
