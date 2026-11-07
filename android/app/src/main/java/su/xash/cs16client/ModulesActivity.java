package su.xash.cs16client;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Install custom native modules (.so) into the shared game tree.
 *
 * A7 build 142 (round 142 item 4, user: "remove scan drop in with new
 * folder just only import and remove modules"): exactly TWO actions left -
 * the in-app file picker (per kind, per Import button) and REMOVE on each
 * installed row. The drop-in folder scan is gone; the GitHub catalog keeps
 * offering published modules. ModuleInstaller places the .so with the
 * right ABI suffix and appends the registration line to modules.ini /
 * metamod plugins.ini. A target-game toggle picks cstrike or valve.
 */
public class ModulesActivity extends Activity {
	private static final int REQ_AMXX = 101;
	private static final int REQ_METAMOD = 102;

	private String game = ModuleInstaller.GAME_CSTRIKE;
	private LinearLayout list;
	private TextView empty;
	private Button targetCstrike;
	private Button targetValve;
	private LinearLayout catalogList;
	private TextView catalogStatus;
	private List<ModuleCatalog.Module> catalog = new ArrayList<ModuleCatalog.Module>();

	@Override
	protected void onCreate( Bundle savedInstanceState ) {
		super.onCreate( savedInstanceState );
		setContentView( R.layout.activity_modules );

		list = (LinearLayout) findViewById( R.id.modules_list );
		empty = (TextView) findViewById( R.id.modules_empty );
		targetCstrike = (Button) findViewById( R.id.target_cstrike );
		targetValve = (Button) findViewById( R.id.target_valve );
		catalogList = (LinearLayout) findViewById( R.id.catalog_list );
		catalogStatus = (TextView) findViewById( R.id.catalog_status );

		targetCstrike.setOnClickListener( new View.OnClickListener() {
			@Override
			public void onClick( View v ) {
				setGame( ModuleInstaller.GAME_CSTRIKE );
			}
		});
		targetValve.setOnClickListener( new View.OnClickListener() {
			@Override
			public void onClick( View v ) {
				setGame( ModuleInstaller.GAME_VALVE );
			}
		});

		((Button) findViewById( R.id.install_amxx )).setOnClickListener(
			new View.OnClickListener() {
			@Override
			public void onClick( View v ) {
				pick( REQ_AMXX );
			}
		});
		((Button) findViewById( R.id.install_metamod )).setOnClickListener(
			new View.OnClickListener() {
			@Override
			public void onClick( View v ) {
				pick( REQ_METAMOD );
			}
		});
		// A7 build 142 (round 142 item 4): the scan-drop-in button and its
		// folder hint are GONE from this screen - Import + Remove are the
		// only actions left (activity_modules.xml no longer maps either)

		setGame( game );
		refreshCatalog();
		TabBar.init( this, TabBar.TAB_MODULES );
	}

	// ------------------------------------------------------------------
	// target game toggle
	// ------------------------------------------------------------------

	private void setGame( String g ) {
		game = g;
		boolean cs = ModuleInstaller.GAME_CSTRIKE.equals( g );
		targetCstrike.setBackgroundResource( cs
			? R.drawable.btn_gold : R.drawable.btn_neutral );
		targetValve.setBackgroundResource( cs
			? R.drawable.btn_neutral : R.drawable.btn_gold );
		targetCstrike.setTextColor( cs ? 0xFFFFFFFF : 0xFFAEB7C6 );
		targetValve.setTextColor( cs ? 0xFFAEB7C6 : 0xFFFFFFFF );
		refresh();
		renderCatalog();
	}

	// ------------------------------------------------------------------
	// installed list
	// ------------------------------------------------------------------

	private void refresh() {
		List<ModuleInstaller.Entry> entries = ModuleInstaller.list( game );
		list.removeAllViews();
		empty.setVisibility( entries.isEmpty() ? View.VISIBLE : View.GONE );
		for( final ModuleInstaller.Entry e : entries )
			list.addView( makeRow( e ));
	}

	private View makeRow( final ModuleInstaller.Entry e ) {
		LinearLayout row = new LinearLayout( this );
		row.setOrientation( LinearLayout.HORIZONTAL );
		row.setGravity( Gravity.CENTER_VERTICAL );
		row.setBackgroundResource( R.drawable.card_dark );
		int pad = dp( 13 );
		row.setPadding( pad, pad, pad, pad );
		LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
			LinearLayout.LayoutParams.MATCH_PARENT,
			LinearLayout.LayoutParams.WRAP_CONTENT );
		rowLp.bottomMargin = dp( 8 );
		row.setLayoutParams( rowLp );

		LinearLayout col = new LinearLayout( this );
		col.setOrientation( LinearLayout.VERTICAL );
		col.setLayoutParams( new LinearLayout.LayoutParams( 0,
			LinearLayout.LayoutParams.WRAP_CONTENT, 1f ));

		TextView name = new TextView( this );
		name.setText( e.base );
		name.setTextColor( 0xFFE8ECF4 );
		name.setTextSize( 15 );
		name.setTypeface( null, Typeface.BOLD );
		col.addView( name );

		TextView sub = new TextView( this );
		sub.setText(( e.kind == ModuleInstaller.KIND_AMXX
			? "AMXX module" : "Metamod plugin" )
			+ "  \u00b7  " + ( e.abi != null ? e.abi : "-" )
			+ ( e.present ? "" : "  \u00b7  missing .so" ));
		sub.setTextColor( e.present ? 0xFF7E8CA0 : 0xFFFF6B61 );
		sub.setTextSize( 12 );
		LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
			LinearLayout.LayoutParams.MATCH_PARENT,
			LinearLayout.LayoutParams.WRAP_CONTENT );
		subLp.topMargin = dp( 2 );
		sub.setLayoutParams( subLp );
		col.addView( sub );

		row.addView( col );

		TextView remove = new TextView( this );
		remove.setText( "REMOVE" );
		remove.setTextColor( 0xFFFF6B61 );
		remove.setTextSize( 12 );
		remove.setTypeface( null, Typeface.BOLD );
		remove.setGravity( Gravity.CENTER );
		remove.setBackgroundResource( R.drawable.btn_neutral );
		remove.setPadding( dp( 12 ), dp( 9 ), dp( 12 ), dp( 9 ));
		LinearLayout.LayoutParams rmLp = new LinearLayout.LayoutParams(
			LinearLayout.LayoutParams.WRAP_CONTENT,
			LinearLayout.LayoutParams.WRAP_CONTENT );
		rmLp.leftMargin = dp( 10 );
		remove.setLayoutParams( rmLp );
		remove.setOnClickListener( new View.OnClickListener() {
			@Override
			public void onClick( View v ) {
				ModuleInstaller.remove( game, e );
				Toast.makeText( ModulesActivity.this,
					e.base + " removed", Toast.LENGTH_SHORT ).show();
				refresh();
			}
		});
		row.addView( remove );

		return row;
	}

	// ------------------------------------------------------------------
	// in-app picker
	// ------------------------------------------------------------------

	private void pick( int req ) {
		Intent i = new Intent( Intent.ACTION_OPEN_DOCUMENT );
		i.addCategory( Intent.CATEGORY_OPENABLE );
		i.setType( "*/*" );
		i.putExtra( Intent.EXTRA_MIME_TYPES, new String[] {
			"application/octet-stream",
			"application/x-sharedlib",
			"application/x-object",
			"*/*"
		});
		try {
			startActivityForResult( i, req );
		} catch( Throwable t ) {
			Toast.makeText( this, "No file picker available",
				Toast.LENGTH_LONG ).show();
		}
	}

	@Override
	protected void onActivityResult( int requestCode, int resultCode, Intent data ) {
		super.onActivityResult( requestCode, resultCode, data );
		if( resultCode != RESULT_OK || data == null || data.getData() == null )
			return;
		if( requestCode != REQ_AMXX && requestCode != REQ_METAMOD )
			return;

		final int kind = requestCode == REQ_AMXX
			? ModuleInstaller.KIND_AMXX : ModuleInstaller.KIND_METAMOD;
		final Uri uri = data.getData();
		final String name = queryDisplayName( uri );

		new Thread( new Runnable() {
			@Override
			public void run() {
				final ModuleInstaller.Result r = installFromUri( kind, uri, name );
				runOnUiThread( new Runnable() {
					@Override
					public void run() {
						Toast.makeText( ModulesActivity.this, r.message,
							Toast.LENGTH_LONG ).show();
						refresh();
					}
				});
			}
		}).start();
	}

	private ModuleInstaller.Result installFromUri( int kind, Uri uri,
			String name ) {
		File tmp = null;
		try {
			tmp = File.createTempFile( "modinst", ".so", getCacheDir() );
			InputStream in = getContentResolver().openInputStream( uri );
			if( in == null )
				return new ModuleInstaller.Result( false,
					"cannot open the selected file" );
			OutputStream out = new FileOutputStream( tmp );
			try {
				byte[] buf = new byte[65536];
				int n;
				while(( n = in.read( buf )) > 0 )
					out.write( buf, 0, n );
			} finally {
				out.close();
				in.close();
			}
			return ModuleInstaller.install( kind, game, tmp,
				name != null ? name : tmp.getName() );
		} catch( Throwable t ) {
			return new ModuleInstaller.Result( false,
				"install error: " + t.getMessage() );
		} finally {
			if( tmp != null && tmp.exists() )
				tmp.delete();
		}
	}

	private String queryDisplayName( Uri uri ) {
		try {
			Cursor c = getContentResolver().query( uri,
				new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null );
			if( c != null ) {
				try {
					if( c.moveToFirst()) {
						int idx = c.getColumnIndex( OpenableColumns.DISPLAY_NAME );
						if( idx >= 0 )
							return c.getString( idx );
					}
				} finally {
					c.close();
				}
			}
		} catch( Throwable ignored ) {
		}
		return null;
	}

	// ------------------------------------------------------------------
	// A7 build 142 (round 142 item 4): the drop-in folder scan section is
	// GONE - scanDropIn() and ModuleInstaller.installDropIn()/installDir()
	// were removed with it ("just only import and remove modules"). The
	// Import pickers above and each installed row's REMOVE are the whole
	// install surface now.
	// ------------------------------------------------------------------

	// ------------------------------------------------------------------
	// catalog (live from github.com/venomxtreme-cs/cs16fresh-modules)
	// ------------------------------------------------------------------

	private void refreshCatalog() {
		catalogStatus.setText( "Loading catalog from GitHub..." );
		new Thread( new Runnable() {
			@Override
			public void run() {
				List<ModuleCatalog.Module> result = null;
				String error = null;
				try {
					result = ModuleCatalog.fetch();
				} catch( Throwable t ) {
					error = t.getMessage() != null ? t.getMessage() : "network error";
				}
				final List<ModuleCatalog.Module> fetched = result;
				final String err = error;
				runOnUiThread( new Runnable() {
					@Override
					public void run() {
						if( isFinishing() || isDestroyed() )
							return;
						if( fetched == null ) {
							catalogStatus.setText( "Catalog unavailable (" + err
								+ "). It refreshes each time you open this screen." );
							return;
						}
						catalog = fetched;
						renderCatalog();
					}
				});
			}
		}, "module-catalog" ).start();
	}

	private void renderCatalog() {
		catalogList.removeAllViews();
		int shown = 0;
		for( final ModuleCatalog.Module m : catalog ) {
			if( !game.equals( m.game ))
				continue;	// only the selected target game
			catalogList.addView( makeCatalogRow( m ));
			shown++;
		}
		catalogStatus.setText( shown > 0
			? "Published modules for this game. Install straight from GitHub - "
				+ "new ones appear here automatically."
			: "No published modules for this game yet." );
	}

	private View makeCatalogRow( final ModuleCatalog.Module m ) {
		final boolean installed = ModuleCatalog.isInstalled( m.game, m.kind, m.name );
		final String instSha = installed
			? ModuleCatalog.installedSha( m.game, m.name ) : null;
		// UPDATE only when the published content actually differs from what is
		// installed. A module installed before digest tracking (no recorded
		// sha) counts as stale so a real admin update still surfaces; one tap
		// pins it and it stops offering UPDATE until the next upload.
		final boolean updatable = installed
			&& ( m.sha.isEmpty() || instSha == null || !m.sha.equals( instSha ));
		final boolean current = installed && !updatable;
		final String idleLabel = !installed ? "INSTALL"
			: ( updatable ? "UPDATE" : "INSTALLED" );

		LinearLayout row = new LinearLayout( this );
		row.setOrientation( LinearLayout.HORIZONTAL );
		row.setGravity( Gravity.CENTER_VERTICAL );
		row.setBackgroundResource( R.drawable.card_dark );
		int pad = dp( 13 );
		row.setPadding( pad, pad, pad, pad );
		LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
			LinearLayout.LayoutParams.MATCH_PARENT,
			LinearLayout.LayoutParams.WRAP_CONTENT );
		rowLp.bottomMargin = dp( 8 );
		row.setLayoutParams( rowLp );

		LinearLayout col = new LinearLayout( this );
		col.setOrientation( LinearLayout.VERTICAL );
		col.setLayoutParams( new LinearLayout.LayoutParams( 0,
			LinearLayout.LayoutParams.WRAP_CONTENT, 1f ));

		TextView name = new TextView( this );
		name.setText( m.name );
		name.setTextColor( 0xFFE8ECF4 );
		name.setTextSize( 15 );
		name.setTypeface( null, Typeface.BOLD );
		col.addView( name );

		TextView sub = new TextView( this );
		sub.setText(( m.kind == ModuleInstaller.KIND_AMXX
			? "AMXX module" : "Metamod plugin" )
			+ "  \u00b7  " + ( "valve".equals( m.game )
				? "Half-Life" : "Counter-Strike" )
			+ "  \u00b7  " + m.soPaths.size() + " ABI"
			+ ( installed ? ( current ? "  \u00b7  up to date"
				: "  \u00b7  update ready" ) : "" ));
		sub.setTextColor( current ? 0xFF7E8CA0 : 0xFFFFC25E );
		sub.setTextSize( 12 );
		LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
			LinearLayout.LayoutParams.MATCH_PARENT,
			LinearLayout.LayoutParams.WRAP_CONTENT );
		subLp.topMargin = dp( 2 );
		sub.setLayoutParams( subLp );
		col.addView( sub );

		row.addView( col );

		final TextView action = new TextView( this );
		action.setText( idleLabel );
		action.setTextSize( 12 );
		action.setTypeface( null, Typeface.BOLD );
		action.setGravity( Gravity.CENTER );
		action.setPadding( dp( 12 ), dp( 9 ), dp( 12 ), dp( 9 ));
		if( current ) {
			// nothing new published - not an action, just a state
			action.setTextColor( 0xFFAEB7C6 );
			action.setBackgroundResource( R.drawable.btn_neutral );
			action.setEnabled( false );
		} else {
			action.setTextColor( 0xFFFFFFFF );
			action.setBackgroundResource( R.drawable.btn_gold );
		}
		LinearLayout.LayoutParams actLp = new LinearLayout.LayoutParams(
			LinearLayout.LayoutParams.WRAP_CONTENT,
			LinearLayout.LayoutParams.WRAP_CONTENT );
		actLp.leftMargin = dp( 10 );
		action.setLayoutParams( actLp );
		action.setOnClickListener( new View.OnClickListener() {
			@Override
			public void onClick( View v ) {
				action.setEnabled( false );
				action.setText( "..." );
				new Thread( new Runnable() {
					@Override
					public void run() {
						final ModuleInstaller.Result r =
							ModuleCatalog.install( ModulesActivity.this, m );
						runOnUiThread( new Runnable() {
							@Override
							public void run() {
								if( isFinishing() || isDestroyed() )
									return;
								action.setEnabled( true );
								action.setText( idleLabel );
								Toast.makeText( ModulesActivity.this, r.message,
									Toast.LENGTH_LONG ).show();
								refresh();
								renderCatalog();
							}
						});
					}
				}, "module-install" ).start();
			}
		});
		row.addView( action );

		return row;
	}

	private int dp( int v ) {
		return Math.round( v * getResources().getDisplayMetrics().density );
	}
}
