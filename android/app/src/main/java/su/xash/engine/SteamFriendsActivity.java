package su.xash.engine;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;

/**
 * A7: Steam friends screen - the "connect Steam on Android, come back to
 * Counter-Strike" flow. A plain WebView on top of the running game:
 *
 *   Steam ID  - type a 7656... number (or STEAM_0:0:1) and Connect:
 *               the public profile XML fetches id + name + photo WITHOUT
 *               any password (build 141 - the primary connect path now)
 *   Sign in   - steamcommunity.com/login (cookies persist in this app, so
 *               the session survives leaving the screen)
 *   Friends   - the account's friends page; the names are scraped out of it
 *               with JS and written to <gamedir>/friends/names.txt, which
 *               the menu and the scoreboard read to badge Steam friends
 *   Close     - finish(), the game resumes exactly where it was
 *
 * No Steam Web API key is used: everything comes from the logged-in
 * session already in the WebView.
 */
public class SteamFriendsActivity extends Activity {
	private static final String TAG = "SteamFriends";

	private static final String URL_LOGIN   = "https://steamcommunity.com/login/";
	private static final String URL_FRIENDS = "https://steamcommunity.com/my/friends/";

	// JS: first selector that produces nodes wins; also pulls the signed-in
	// steamid64 out of the page's embedded JSON, plus every "join this
	// server" link (steam://connect/IP:PORT) with the friend it belongs to.
	// A7 round 100: it also walks up from each persona node to the avatar
	// <img> of that friend and collects {name,src} pairs - the scoreboard
	// shows them in its logo column - and takes my own header photo.
	// Returns {"id":..,"self":..,"names":[..],"ents":[..],"servers":[..]}
	private static final String EXTRACT_JS =
		"(function(){" +
		"var out=[];var ents=[];" +
		"var sels=['#memberList .persona','.friend_block_v2 .persona'," +
		"'#memberContents .selectable_row .personaname','.selectable_row .persona'," +
		"'.friend_block .personaname','a.personaname'];" +
		"for(var i=0;i<sels.length;i++){" +
		" var n=document.querySelectorAll(sels[i]);" +
		" if(n.length>0){for(var j=0;j<n.length;j++){" +
		"  var t=(n[j].textContent||'').trim().split('\\n')[0];" +
		"  if(!t||out.indexOf(t)>=0)continue;" +
		"  out.push(t);" +
		"  var root=n[j];var img=null;" +
		"  for(var d=0;d<4&&root;d++){if(root.querySelector){img=root.querySelector('img');}if(img)break;root=root.parentElement;}" +
		"  var src='';if(img){src=img.getAttribute('src')||img.getAttribute('data-src')||'';}" +
		"  ents.push({name:t,src:src});" +
		"}break;}" +
		"}" +
		"var h=document.documentElement.innerHTML||'';" +
		"var m=h.match(/\"steamid\"\\s*:?\\s*\"?(7656\\d{13})\"?/);" +
		"if(!m)m=h.match(/profiles\\/(7656\\d{13})/);" +
		"var me=document.querySelector('img.avatar');var self='';" +
		"if(me){self=me.getAttribute('src')||me.getAttribute('data-src')||'';}" +
		// A7 build 139 (items 3+4): my own persona name. First the
		// embedded user JSON that sits right after our own steamid (the
		// same object the id regex above found), else the text of the
		// header avatar's profile link. Single-quoted JS only - like the
		// note below, no regex backslashes to mis-escape Java-side.
		"var meName='';" +
		"if(m){var p=h.indexOf('\"steamid\":\"'+m[1]+'\"');if(p<0)p=h.indexOf('\"steamid\":'+m[1]);" +
		"if(p>=0){var seg=h.substring(p,p+800);var pm=seg.match(/\"personaname\":\"([^\"]*)\"/);if(pm)meName=pm[1];}}" +
		"if(!meName&&me&&me.closest){var a=me.closest('a');if(a){meName=(a.textContent||'').trim();}}" +
		"var res={id:m?m[1]:'',self:self,me:meName,names:out,ents:ents,servers:[]};" +
		// [.] instead of an escaped dot keeps this string free of
		// backslashes: inside a character class the dot is literal, and
		// Java-side escaping of an ES5 regex is where mistakes hide
		"var re=new RegExp('steam://connect/[0-9]{1,3}[.][0-9]{1,3}[.][0-9]{1,3}[.][0-9]{1,3}:[0-9]+','g');" +
		"var mm;" +
		"while((mm=re.exec(h))!==null){" +
		" var addr=mm[0].substring(mm[0].lastIndexOf('/')+1);" +
		" var dup=false;" +
		" for(var k=0;k<res.servers.length;k++) if(res.servers[k].addr===addr) dup=true;" +
		" if(dup) continue;" +
		" var nm='';" +
		" var pre=h.substring(Math.max(0,mm.index-5000),mm.index);" +
		" var kk=pre.lastIndexOf('\"personaname\":\"');" +
		" if(kk>=0){var e=pre.indexOf('\"',kk+15); if(e>kk) nm=pre.substring(kk+15,e);}" +
		" res.servers.push({addr:addr,name:nm||addr});" +
		"}" +
		"return JSON.stringify(res);" +
		"})()";

	private WebView mWeb;
	private TextView mStatus;
	// A7 build 141 (round 141 item 1): the Steam ID entry box - the
	// password-free connect path he asked for ("let's do other way to
	// login like steam id or?")
	private EditText mSteamIdInput;
	private File mFriendsDir;
	// <basedir>/<gamedir>: my own avatar goes to media/avatars under it
	private File mGameDir;
	private String mSteamId = "";

	// A7 build 138 (round 137 item 1, user: "fix login steam just using
	// chrome if it signed in just turn back to cs16fresh app"): Chrome
	// and this WebView never share cookies and there is no return_url
	// bounce (no server of ours), so the browser can only be step one of
	// the flow - it signs you in quickly, and the moment we are back the
	// friends page continues the sign-in inside the app (Chrome's saved
	// password comes over through Android autofill). mReturnFromBrowser
	// is the one-shot "we just came back from the hand-off" latch;
	// mSignedInViaBrowser stays set until extract() sees the Steam ID,
	// which closes this screen straight back to the game.
	private boolean mReturnFromBrowser = false;
	private boolean mSignedInViaBrowser = false;

	@Override
	protected void onCreate( Bundle savedInstanceState )
	{
		super.onCreate( savedInstanceState );

		Intent intent = getIntent();
		String basedir = intent != null ? intent.getStringExtra( "basedir" ) : null;
		String gamedir = intent != null ? intent.getStringExtra( "gamedir" ) : null;

		if( basedir == null )
			basedir = new File( android.os.Environment.getExternalStorageDirectory(), "xash" ).getAbsolutePath();
		if( gamedir == null )
			gamedir = "cstrike";

		mGameDir = new File( basedir, gamedir );
		mFriendsDir = new File( mGameDir, "friends" );

		CookieManager cookies = CookieManager.getInstance();
		cookies.setAcceptCookie( true );

		buildLayout();

		mWeb.getSettings().setJavaScriptEnabled( true );
		mWeb.getSettings().setDomStorageEnabled( true );
		mWeb.getSettings().setUserAgentString( mWeb.getSettings().getUserAgentString() + " XashCS16Friends/1" );
		// the friends list is a desktop page: show it at a phone-ish zoom
		mWeb.getSettings().setLoadWithOverviewMode( true );
		mWeb.getSettings().setUseWideViewPort( true );
		mWeb.getSettings().setLayoutAlgorithm( WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING );

		final Activity self = this;
		mWeb.setWebViewClient( new WebViewClient()
		{
			@Override
			public boolean shouldOverrideUrlLoading( WebView view, String url )
			{
				// keep everything inside the screen - an external link would
				// otherwise hand the session over to the system browser
				view.loadUrl( url );
				return true;
			}

			@Override
			public void onPageFinished( WebView view, String url )
			{
				extract( url );
			}
		});

		mSteamId = readAccountFile( "steamid" );
		setStatus( mSteamId.isEmpty() ? "Not signed in" : ( "Steam ID: " + mSteamId ));

		if( mSteamId.isEmpty())
		{
			// A7 build 141 (round 141 item 1, user: "fix steam login, let's
			// do other way to login like steam id or?"): the screen NO
			// LONGER bounces to Chrome by itself - typing the Steam ID in
			// the row below is the primary connect now (no password, no
			// browser). The Sign in button keeps the old Chrome hand-off
			// for friends scraping, which still needs a signed-in session.
			setStatus( "Enter your Steam ID below and tap Connect" );
		}
		else
		{
			mWeb.loadUrl( URL_FRIENDS );
		}
	}

	// A7 build 138 (round 137 item 1): hand the Steam sign-in to the
	// user's browser - Chrome first (it is what the user asked for, and
	// its saved passwords make the login one tap), any other browser as
	// a fallback, and the in-app WebView as the last resort when no
	// browser exists at all. The ?redir= makes Steam come back to the
	// friends URL, which is where we continue when the user returns.
	private void openChromeSignIn()
	{
		String url = URL_LOGIN;
		try
		{
			url += "?redir=" + java.net.URLEncoder.encode( URL_FRIENDS, "UTF-8" );
		}
		catch( Throwable ignored )
		{
		}

		final String loginUrl = url;

		// 1) Chrome by name
		try
		{
			Intent chrome = new Intent( Intent.ACTION_VIEW, Uri.parse( loginUrl ));
			chrome.setPackage( "com.android.chrome" );
			chrome.addCategory( Intent.CATEGORY_BROWSABLE );
			startActivity( chrome );
			mReturnFromBrowser = true;
			setStatus( "Opening Chrome - sign in, then you come back here" );
			Log.i( TAG, "[A7] build138: Steam sign-in handed to Chrome" );
			return;
		}
		catch( Throwable ignored )
		{
		}

		// 2) any other installed browser
		try
		{
			Intent any = new Intent( Intent.ACTION_VIEW, Uri.parse( loginUrl ));
			any.addCategory( Intent.CATEGORY_BROWSABLE );
			startActivity( any );
			mReturnFromBrowser = true;
			setStatus( "Opening the browser - sign in, then you come back here" );
			Log.i( TAG, "[A7] build138: Steam sign-in handed to the system browser (no Chrome)" );
			return;
		}
		catch( Throwable ignored )
		{
		}

		// 3) no browser on the device at all - sign in right here
		Log.i( TAG, "[A7] build138: no browser found, in-app Steam sign-in fallback" );
		setStatus( "Sign in below (no browser available)" );
		mWeb.loadUrl( loginUrl );
	}

	// A7 build 136 (item 1, user: connect/disconnect must both work):
	// sign out for real - WebView cookies go, friends/steamid.txt is
	// deleted, so this status bar, the launcher Settings card and the
	// in-game Options Steam row all read "not signed in" again
	private void signOut()
	{
		try {
			CookieManager cookies = CookieManager.getInstance();
			cookies.removeAllCookies( null );
			cookies.flush();
		} catch( Throwable ignored ) {
		}

		try {
			File f = new File( mFriendsDir, "steamid.txt" );
			if( f.isFile()) {
				if( f.delete())
					Log.i( TAG, "[A7] build136: steam sign out (steamid.txt removed)" );
			}
		} catch( Throwable ignored ) {
		}

		mSteamId = "";
		setStatus( "Not signed in" );
		mWeb.loadUrl( URL_LOGIN );
	}

	// ------------------------------------------------------------------
	// A7 build 141 (round 141 item 1, user: "fix steam login, let's do
	// other way to login like steam id or?"): connect by Steam ID.
	//
	// No password, no WebView session, NO Steam Web API key (project
	// rule): the public profile XML (steamcommunity.com/profiles/<id>/?xml=1)
	// supplies the persona name and the full photo - the exact endpoint
	// fetchMyAvatar already relies on. playerdb.co is the mirror when
	// steamcommunity does not answer (filtered networks), the same mirror
	// the engine's avatar fetcher uses.
	//
	// The writes are IDENTICAL to the Chrome flow (friends/steamid.txt +
	// friends/name.txt + media/avatars/avatar_self.png), so everything
	// downstream is untouched: the launcher Settings card, the in-game
	// Options Steam row and, since build 140, the live menu signal
	// (notifyAvatarSaved -> @cl_avatargen -> UI_ApplySteamPersona).
	// ------------------------------------------------------------------

	/** user input to a steamid64: a plain 7656... 17-digit number (any
	 *  junk around it is stripped - a pasted profile URL works too) or the
	 *  legacy STEAM_X:Y:Z form. Returns "" when nothing usable. */
	private static String normalizeSteamId( String raw )
	{
		if( raw == null )
			return "";

		String s = raw.trim();

		java.util.regex.Matcher legacy = java.util.regex.Pattern.compile(
			"STEAM_([0-5]):([0-1]):([0-9]+)",
			java.util.regex.Pattern.CASE_INSENSITIVE ).matcher( s );
		if( legacy.find())
		{
			// steamid64 = base + Z * 2 + Y (universe 0/1 share the public base)
			try {
				long z = Long.parseLong( legacy.group( 3 ));
				long y = Long.parseLong( legacy.group( 2 ));
				return String.valueOf( 76561197960265728L + z * 2L + y );
			} catch( Throwable ignored ) {
			}
		}

		// digits only, then the shape check happens at the caller
		return s.replaceAll( "[^0-9]", "" );
	}

	/** first "field":"value" of a small JSON document (the playerdb
	 *  mirror has no schema this screen should depend on beyond that). */
	private static String jsonStringField( String json, String field )
	{
		java.util.regex.Matcher m = java.util.regex.Pattern.compile(
			"\"" + field + "\"\\s*:\\s*\"([^\"]+)\"" ).matcher( json );
		return m.find() ? m.group( 1 ) : null;
	}

	/** the Connect button: validate, look the profile up off the UI
	 *  thread, write id + persona + avatar, signal the running menu. */
	private void connectBySteamId( String raw )
	{
		final String id = normalizeSteamId( raw );
		if( !id.matches( "7656[0-9]{13}" ))
		{
			setStatus( "Not a Steam ID - enter 7656... (17 digits) or STEAM_0:0:1" );
			return;
		}

		setStatus( "Looking up Steam ID " + id + " ..." );

		new Thread( new Runnable()
		{
			@Override
			public void run()
			{
				String xml = httpGetString(
					"https://steamcommunity.com/profiles/" + id + "/?xml=1" );

				String name = null, avatar = null;
				boolean profileSeen = false;
				// Private / friends-only: the ID is valid but Steam hides
				// the persona photo - save the id and tell him to open the
				// profile instead of reporting a failure
				boolean hiddenProfile = false;

				if( xml != null && xml.contains( "<profile>" ))
				{
					profileSeen = true;
					String visibility = extractXmlTag( xml, "privacyVisibility" );
					hiddenProfile = "Private".equalsIgnoreCase( visibility )
							|| "FriendsOnly".equalsIgnoreCase( visibility );
					name = extractXmlTag( xml, "personaName" );
					avatar = extractXmlTag( xml, "avatarFull" );
				}

				// steamcommunity blocked/smells like a bot -> the playerdb
				// mirror (same one XashActivity.avatarFetch falls back to)
				if( name == null || name.isEmpty())
				{
					String json = httpGetString(
						"https://playerdb.co/api/player/steam/" + id );
					if( json != null )
					{
						String mirrorName = jsonStringField( json, "personaname" );
						if( mirrorName == null )
							mirrorName = jsonStringField( json, "username" );
						if( mirrorName == null )
							mirrorName = jsonStringField( json, "steamName" );
						if( mirrorName != null && !mirrorName.isEmpty())
						{
							name = mirrorName;
							profileSeen = true;
							hiddenProfile = false;
						}
						if( avatar == null || avatar.isEmpty())
							avatar = jsonStringField( json, "avatar" );
					}
				}

				if( xml == null && !profileSeen && avatar == null )
				{
					finishOnUi( "No answer from Steam - check internet, tap Connect again" );
					return;
				}
				if( !profileSeen && avatar == null )
				{
					finishOnUi( "Steam ID " + id + " not found - check the number" );
					return;
				}

				// ------- the same writes the Chrome flow produces -------
				boolean changed = false;

				if( !id.equals( mSteamId )
						|| !new File( mFriendsDir, "steamid.txt" ).isFile())
				{
					mSteamId = id;
					writeAccountFile( "steamid", id );
					changed = true;
				}

				if( !hiddenProfile && name != null && !name.isEmpty())
				{
					String clean = sanitizePersona( name );
					if( !clean.isEmpty() && !clean.equals( readAccountFile( "name" )))
					{
						writeAccountFile( "name", clean );
						changed = true;
						name = clean;
					}
				}

				if( !hiddenProfile )
					fetchMyAvatar( avatar != null ? avatar : "" );

				// ------- status + the live menu signal (build 140) -------
				String status;
				if( hiddenProfile )
					status = "Steam ID: " + id + " - profile is private, set it to Public for name/photo";
				else if( name != null && !name.isEmpty())
					status = "Steam ID: " + id + "  -  " + name;
				else
					status = "Steam ID: " + id + " - no name returned";
				finishOnUi( status );

				if( changed )
				{
					// marker scanned from the dex by scan112
					Log.i( TAG, "[A7] build141: steam connected via ID entry " + id );
					XashActivity.notifyAvatarSaved();
				}
			}
		}, "steam-id-lookup" ).start();
	}

	/** setStatus via the UI thread - the ID lookup runs on a worker. */
	private void finishOnUi( final String s )
	{
		runOnUiThread( new Runnable()
		{
			@Override
			public void run()
			{
				setStatus( s );
			}
		});
	}

	// ------------------------------------------------------------------
	// UI: a thin control bar over the full-height WebView
	// ------------------------------------------------------------------
	private void buildLayout()
	{
		LinearLayout root = new LinearLayout( this );
		root.setOrientation( LinearLayout.VERTICAL );
		root.setBackgroundColor( Color.BLACK );

		LinearLayout bar = new LinearLayout( this );
		bar.setOrientation( LinearLayout.HORIZONTAL );
		bar.setPadding( 8, 8, 8, 8 );

		mStatus = new TextView( this );
		mStatus.setTextColor( Color.LTGRAY );
		mStatus.setTextSize( 14 );
		mStatus.setPadding( 8, 12, 8, 0 );

		addButton( bar, "Back", v -> {
			if( mWeb.canGoBack()) mWeb.goBack();
		});
		// A7 build 138 (round 137 item 1): the sign-in button goes to
		// Chrome - on return the flow finishes itself in-app and the
		// screen auto-closes back to cs16fresh once the ID is captured
		addButton( bar, "Sign in", v -> openChromeSignIn());
		addButton( bar, "Friends", v -> mWeb.loadUrl( URL_FRIENDS ));
		// A7 build 136 (item 1): a real Disconnect - the old screen had no
		// way out of an account, so "connected" stuck forever (the menu
		// options row and this status bar would keep showing the id)
		addButton( bar, "Sign out", v -> signOut());
		addButton( bar, "Close", v -> finish());

		LinearLayout statusRow = new LinearLayout( this );
		statusRow.setOrientation( LinearLayout.HORIZONTAL );
		statusRow.addView( mStatus, new LinearLayout.LayoutParams(
			ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT ));

		bar.addView( statusRow, new LinearLayout.LayoutParams( 0,
			ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f ));

		mWeb = new WebView( this );

		// A7 build 141 (round 141 item 1, user: "fix steam login, let's do
		// other way to login like steam id or?"): Steam ID entry row - connect
		// without any password or WebView session. The public profile XML
		// carries the persona name + photo (NO Steam Web API key, the same
		// endpoint fetchMyAvatar already uses), so one number in, one
		// connect out: friends/steamid.txt + friends/name.txt + the avatar,
		// exactly the files the Chrome flow writes - the launcher card, the
		// in-game Options Steam row and the build-140 live menu signal all
		// keep working unchanged. Chrome stays as the second door (Friends
		// scraping still needs a signed-in session).
		LinearLayout idRow = new LinearLayout( this );
		idRow.setOrientation( LinearLayout.HORIZONTAL );
		idRow.setPadding( 8, 0, 8, 4 );

		mSteamIdInput = new EditText( this );
		mSteamIdInput.setHint( "Steam ID (7656... or STEAM_0:0:1)" );
		// text, not class-number: the legacy STEAM_0:0:1 form carries
		// colons (normalizeSteamId() accepts both shapes)
		mSteamIdInput.setInputType( InputType.TYPE_CLASS_TEXT
			| InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
			| InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD );
		mSteamIdInput.setTextColor( Color.LTGRAY );
		mSteamIdInput.setHintTextColor( 0xFF7E8CA0 );
		mSteamIdInput.setTextSize( 14 );
		mSteamIdInput.setSingleLine( true );
		idRow.addView( mSteamIdInput, new LinearLayout.LayoutParams(
			0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f ));
		addButton( idRow, "Connect", v -> connectBySteamId( mSteamIdInput.getText().toString()));

		root.addView( bar, new LinearLayout.LayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT ));
		root.addView( idRow, new LinearLayout.LayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT ));
		root.addView( mWeb, new LinearLayout.LayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f ));

		setContentView( root );
	}

	private void addButton( LinearLayout bar, String label, android.view.View.OnClickListener cb )
	{
		Button b = new Button( this );
		b.setText( label );
		b.setTextSize( 13 );
		b.setAllCaps( false );
		b.setOnClickListener( cb );
		bar.addView( b, new LinearLayout.LayoutParams(
			ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT ));
	}

	// ------------------------------------------------------------------
	// extraction + persistence
	// ------------------------------------------------------------------
	@SuppressLint("SetJavaScriptEnabled")
	private void extract( String url )
	{
		// only scrape the friends page; every other page still updates the id
		final boolean friendsPage = url != null && url.contains( "/friends" );

		mWeb.evaluateJavascript( EXTRACT_JS, new ValueCallback<String>()
		{
			@Override
			public void onReceiveValue( String value )
			{
				applyExtract( value, null, friendsPage );
			}
		});
	}

	private void applyExtract( String json, String fallbackUrl, boolean saveFriends )
	{
		if( json == null || "null".equals( json ) || json.isEmpty())
			return;

		try
		{
			JSONObject o = new JSONObject( json );

			// A7 build 140 (round 140 item 3): set when this pass wrote the
			// id or the persona - see the notifyAvatarSaved() below
			boolean accountChanged = false;

			String id = o.optString( "id", "" );
			if( !id.isEmpty() && !id.equals( mSteamId ))
			{
				mSteamId = id;
				writeAccountFile( "steamid", id );
				accountChanged = true;
				// A7 build 136 (item 1): "connected" = id on disk, and the
				// line says Steam ID exactly like the launcher Settings card
				// and the in-game Options row do
				setStatus( "Steam ID: " + id );
				Log.i( TAG, "[A7] build136: steam connected " + id
						+ " (friends/steamid.txt written)" );
			}

			// A7 build 139 (items 3+4, user: "when i connect to steam
			// update steam id, avatar and NAME also"): the persona from
			// the same page payload goes to friends/name.txt - sanitized
			// for the name cvar/userinfo, and only written when it
			// actually CHANGED since the last sign-in. The menu applies
			// it once per change (UI_ApplySteamPersona, remembered in
			// friends/name_applied.txt) so a name typed by hand after the
			// sign-in is never rewritten on a later visit to this screen.
			String me = o.optString( "me", "" );
			if( !mSteamId.isEmpty() && !me.isEmpty())
			{
				String clean = sanitizePersona( me );
				if( !clean.isEmpty() && !clean.equals( readAccountFile( "name" )))
				{
					writeAccountFile( "name", clean );
					accountChanged = true;
					Log.i( TAG, "[A7] build139: steam persona saved '" + clean
							+ "' (friends/name.txt)" );
				}
			}

			// A7 build 140 (round 140 item 3, user: "i already sign in but
			// nothing happened"): build 139 wrote the persona files but only
			// the menu's Show/Reload/Init paths applied them - returning
			// from this screen re-shows nothing, so the name stayed stale
			// while he sat where he was. Signal the running game right after
			// the write: notifyAvatarSaved bumps @cl_avatargen and the menu
			// watches it every frame (UI_UpdateMenu -> UI_ApplySteamPersona),
			// so the new name lands the moment he is back in the game.
			// Engine not running (launcher-side sign-in): the wrapper logs
			// and UI_Init applies the files at the next start, unchanged.
			if( accountChanged )
			{
				Log.i( TAG, "[A7] build140: steam account changed - signaling menu to apply persona" );
				XashActivity.notifyAvatarSaved();
			}

			if( saveFriends )
			{
				ArrayList<String> names = new ArrayList<String>();
				for( int i = 0; i < o.getJSONArray( "names" ).length(); i++ )
				{
					String n = o.getJSONArray( "names" ).getString( i );
					if( n != null && !n.isEmpty() )
						names.add( n );
				}

				// joinable servers ("steam://connect/IP:PORT" links) are rare
				// on the page - they only exist for a friend who is in a
				// server that allows direct connects
				ArrayList<String[]> servers = new ArrayList<String[]>();
				org.json.JSONArray arr = o.optJSONArray( "servers" );
				if( arr != null )
				{
					for( int i = 0; i < arr.length(); i++ )
					{
						JSONObject s = arr.optJSONObject( i );
						if( s == null )
							continue;
						String addr = s.optString( "addr", "" );
						String nm = s.optString( "name", "" );
						if( addr.isEmpty())
							continue;
						servers.add( new String[]{ addr, nm });
					}
				}

				// a logged-out friends page (or another Steam redesign) yields
				// nothing - never overwrite a good list with an empty one
				if( names.isEmpty() && servers.isEmpty())
				{
					setStatus( "Signed in: " + ( mSteamId.isEmpty() ? "?" : mSteamId )
						+ "  -  0 friends read" );
				}
				else
				{
					if( !names.isEmpty())
						writeNames( names );
					if( !servers.isEmpty())
						writeServers( servers );
					setStatus( "Signed in: " + ( mSteamId.isEmpty() ? "?" : mSteamId )
						+ "  -  " + names.size() + " friends / "
						+ servers.size() + " servers saved" );
				}

				// A7 round 100: the scoreboard's logo column - each friend's
				// photo goes to friends/avatars/ with a name -> file map, and
				// my own photo to media/avatars/avatar_self.png (the first
				// entry of my row's chain that the server cannot supply)
				writeFriendAvatars( o.optJSONArray( "ents" ));
				fetchMyAvatar( o.optString( "self", "" ));

				// A7 build 138 (round 137 item 1, the second half of
				// "if it signed in just turn back to cs16fresh app"):
				// the sign-in was started in Chrome and finished in this
				// WebView - the id and the whole friends payload are on
				// disk now, so close the screen and land back in the game
				// without another tap. A screen that was opened without the
				// browser hand-off never takes this branch.
				if( mSignedInViaBrowser && !mSteamId.isEmpty())
				{
					mSignedInViaBrowser = false;
					Log.i( TAG, "[A7] build138: Steam signed in via Chrome, returning to cs16fresh" );
					finish();
				}
			}
		}
		catch( Throwable t )
		{
			// a Steam page re-design just changes the selectors - keep the
			// screen usable instead of dying on JSON
			setStatus( "Could not read the friends list (page layout changed)" );
		}
	}

	private String readAccountFile( String what )
	{
		try
		{
			File f = new File( mFriendsDir, what + ".txt" );
			if( !f.isFile()) return "";
			java.io.BufferedReader r = new java.io.BufferedReader(
				new java.io.InputStreamReader( new java.io.FileInputStream( f ), "UTF-8" ));
			String line = r.readLine();
			r.close();
			return line != null ? line.trim() : "";
		}
		catch( Throwable t )
		{
			return "";
		}
	}

	private void writeAccountFile( String what, String value )
	{
		try
		{
			mFriendsDir.mkdirs();
			OutputStreamWriter w = new OutputStreamWriter(
				new FileOutputStream( new File( mFriendsDir, what + ".txt" ), false ), "UTF-8" );
			w.write( value + "\n" );
			w.close();
		}
		catch( Throwable t )
		{
			// external storage missing - the list simply won't be shared
		}
	}

	// A7 build 139 (items 3+4): persona -> something the name cvar and
	// userinfo accept - no quotes/backslashes/semicolon (userinfo
	// separators), no control characters, trimmed, 31 chars like the
	// menu's name field.
	private static String sanitizePersona( String s )
	{
		StringBuilder b = new StringBuilder( 32 );
		for( int i = 0; i < s.length() && b.length() < 31; i++ )
		{
			char c = s.charAt( i );
			if( c < 0x20 || c == 0x7F || c == '"' || c == '\\' || c == ';' )
				continue;
			b.append( c );
		}
		return b.toString().trim();
	}

	// <gamedir>/friends/names.txt: one friendly name per line, UTF-8. The
	// scoreboard badges a row whose player name matches one of these lines.
	private void writeNames( ArrayList<String> names )
	{
		try
		{
			mFriendsDir.mkdirs();
			OutputStreamWriter w = new OutputStreamWriter(
				new FileOutputStream( new File( mFriendsDir, "names.txt" ), false ), "UTF-8" );
			for( int i = 0; i < names.size(); i++ )
				w.write( names.get( i ) + "\n" );
			w.close();
		}
		catch( Throwable t )
		{
		}
	}

	// <gamedir>/friends/servers.txt: "ip:port<TAB>server name" per line.
	// Find Servers -> Friends parses it (ServerBrowser::LoadFriendsTab) and
	// joins through the address on the first field.
	private void writeServers( ArrayList<String[]> servers )
	{
		try
		{
			mFriendsDir.mkdirs();
			OutputStreamWriter w = new OutputStreamWriter(
				new FileOutputStream( new File( mFriendsDir, "servers.txt" ), false ), "UTF-8" );
			for( int i = 0; i < servers.size(); i++ )
			{
				String addr = servers.get( i )[0];
				String nm = servers.get( i )[1];
				// never write a bare <TAB>: the name column has to show
				// something, the address is the safest fallback
				w.write( addr + "\t" + ( nm.isEmpty() ? addr : nm ) + "\n" );
			}
			w.close();
		}
		catch( Throwable t )
		{
		}
	}

	// ------------------------------------------------------------------
	// A7 round 100: avatar photos for the scoreboard's logo column
	// ------------------------------------------------------------------
	// Steam's avatar srcs are protocol-relative or full https URLs; both
	// come back as one plain https link (and a bare path is absolutised),
	// otherwise the HttpURLConnection would throw on "//host/..."
	private static String normalizeAvatarUrl( String url )
	{
		if( url == null || url.isEmpty())
			return "";

		url = url.trim();

		if( url.startsWith( "//" ))
			return "https:" + url;
		if( url.startsWith( "http://" ) || url.startsWith( "https://" ))
			return url;
		if( url.startsWith( "/" ))
			return "https://steamcommunity.com" + url;

		return "";
	}

	// centre square-crop, then scale: the row plate is small and a
	// non-square photo would be squashed in the scoreboard's logo column
	private static android.graphics.Bitmap squareCrop( android.graphics.Bitmap src, int size )
	{
		int w = src.getWidth(), h = src.getHeight();
		android.graphics.Bitmap made = src;

		if( w != h )
		{
			int c = Math.min( w, h );
			made = android.graphics.Bitmap.createBitmap( src, ( w - c ) / 2, ( h - c ) / 2, c, c );
		}

		if( made.getWidth() != size || made.getHeight() != size )
		{
			android.graphics.Bitmap scaled = android.graphics.Bitmap.createScaledBitmap(
				made, size, size, true );
			if( made != src )
				made.recycle();
			made = scaled;
		}

		return made;
	}

	private void writeTextFile( File f, String text )
	{
		try
		{
			FileOutputStream fos = new FileOutputStream( f, false );
			fos.write( text.getBytes( "UTF-8" ));
			fos.close();
		}
		catch( Throwable t )
		{
		}
	}

	private byte[] httpGet( String url )
	{
		java.net.HttpURLConnection c = null;
		try
		{
			c = ( java.net.HttpURLConnection ) new java.net.URL( url ).openConnection();
			c.setConnectTimeout( 8000 );
			c.setReadTimeout( 10000 );
			c.setInstanceFollowRedirects( true );
			c.setRequestProperty( "User-Agent", "Mozilla/5.0 (Xash3D Android)" );

			java.io.InputStream in = c.getInputStream();
			java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
			byte[] chunk = new byte[16384];
			int n;
			while(( n = in.read( chunk )) > 0 )
			{
				buf.write( chunk, 0, n );
				if( buf.size() > 4 * 1024 * 1024 )
					break;
			}
			in.close();
			return buf.toByteArray();
		}
		catch( Throwable t )
		{
			return null;
		}
		finally
		{
			if( c != null )
				try { c.disconnect(); } catch( Throwable ignored ) {}
		}
	}

	/** the same fetch as httpGet(), decoded as one UTF-8 string -
	 *  null when the network failed (A7 build 141, Steam ID entry). */
	private String httpGetString( String url )
	{
		byte[] data = httpGet( url );
		if( data == null || data.length == 0 )
			return null;
		try {
			return new String( data, 0, data.length, "UTF-8" );
		} catch( Throwable t ) {
			return null;
		}
	}

	/** first <tag>...</tag> value of a small xml page, CDATA stripped */
	private static String extractXmlTag( String xml, String tag )
	{
		String open = "<" + tag + ">";
		String close = "</" + tag + ">";
		int a = xml.indexOf( open );
		int b = xml.indexOf( close, a >= 0 ? a : 0 );

		if( a < 0 || b < 0 )
			return null;

		String v = xml.substring( a + open.length(), b ).trim();
		if( v.startsWith( "<![CDATA[" ) && v.endsWith( "]]>" ))
			v = v.substring( 9, v.length() - 3 ).trim();
		return v;
	}

	/** download every friend's photo into <gamedir>/friends/avatars/ and
	 *  write <gamedir>/friends/avatars.txt as "name<TAB>path" rows -
	 *  the scoreboard reads that map and draws the picture next to the
	 *  matching player's row (a name it doesn't know simply draws none).
	 *  Runs on a worker thread; an empty round never overwrites a good map. */
	private void writeFriendAvatars( final org.json.JSONArray ents )
	{
		if( ents == null || ents.length() == 0 )
			return;

		new Thread( new Runnable()
		{
			@Override
			public void run()
			{
				File dir = new File( mFriendsDir, "avatars" );

				if( !dir.isDirectory() && !dir.mkdirs())
					return;

				StringBuilder map = new StringBuilder();
				int ok = 0;

				try
				{
					for( int i = 0; i < ents.length(); i++ )
					{
						JSONObject e = ents.optJSONObject( i );
						if( e == null )
							continue;

						String name = e.optString( "name", "" ).replace( '\t', ' ' ).replace( '\n', ' ' );
						String url  = normalizeAvatarUrl( e.optString( "src", "" ));

						if( name.isEmpty() || url.isEmpty())
							continue;

						byte[] img = httpGet( url );
						if( img == null || img.length < 64 )
							continue;

						android.graphics.Bitmap bmp = android.graphics.BitmapFactory
							.decodeByteArray( img, 0, img.length );
						if( bmp == null )
							continue;

						android.graphics.Bitmap sq = squareCrop( bmp, 64 );
						bmp.recycle();

						String rel = "friends/avatars/fa_" + ok + ".png";
						FileOutputStream fos = new FileOutputStream( new File( dir, "fa_" + ok + ".png" ));
						sq.compress( android.graphics.Bitmap.CompressFormat.PNG, 90, fos );
						fos.close();
						sq.recycle();

						map.append( name ).append( '\t' ).append( rel ).append( '\n' );
						ok++;
					}
				}
				catch( Throwable t )
				{
					Log.w( TAG, "friend avatars failed", t );
				}

				if( ok > 0 )
					writeTextFile( new File( mFriendsDir, "avatars.txt" ), map.toString());
			}
		}).start();
	}

	/** my own photo - the first entry of my scoreboard row's chain the
	 *  server cannot supply (no *sid on casual xash servers) - saved as
	 *  <gamedir>/media/avatars/avatar_self.png */
	private void fetchMyAvatar( String selfUrl )
	{
		final String pageUrl = normalizeAvatarUrl( selfUrl );
		final String sid = mSteamId;

		new Thread( new Runnable()
		{
			@Override
			public void run()
			{
				try
				{
					String url = pageUrl;

					if( url.isEmpty() && !sid.isEmpty())
					{
						String xml = null;
						byte[] data = httpGet( "https://steamcommunity.com/profiles/" + sid + "/?xml=1" );
						if( data != null )
						{
							xml = new String( data, "UTF-8" );
							url = extractXmlTag( xml, "avatarFull" );
						}
					}

					if( url == null || url.isEmpty())
						return;

					byte[] img = httpGet( url );
					if( img == null || img.length < 64 )
						return;

					android.graphics.Bitmap bmp = android.graphics.BitmapFactory
						.decodeByteArray( img, 0, img.length );
					if( bmp == null )
						return;

					android.graphics.Bitmap sq = squareCrop( bmp, 64 );
					bmp.recycle();

					File dir = new File( mGameDir, "media/avatars" );
					if( !dir.isDirectory() && !dir.mkdirs())
						return;

					FileOutputStream fos = new FileOutputStream( new File( dir, "avatar_self.png" ));
					sq.compress( android.graphics.Bitmap.CompressFormat.PNG, 90, fos );
					fos.close();
					sq.recycle();

					Log.i( TAG, "welcome avatar saved" );

					// A7 build 139 (items 3+4): raise the SAME live-refresh
					// hook the gallery picker uses - the engine bumps
					// @cl_avatargen on its next frame, so the options
					// preview and both scoreboards show the new photo
					// without a rejoin (throws only when the engine libs
					// are not loaded, i.e. a launcher-side sign-in: the
					// file is on disk for the next start then)
					XashActivity.notifyAvatarSaved();
				}
				catch( Throwable t )
				{
					Log.w( TAG, "own avatar failed", t );
				}
			}
		}).start();
	}

	private void setStatus( String s )
	{
		mStatus.setText( s );
	}

	@Override
	protected void onPause()
	{
		super.onPause();
		if( mWeb != null )
			mWeb.onPause();
		CookieManager.getInstance().flush();
	}

	@Override
	protected void onResume()
	{
		super.onResume();
		if( mWeb != null )
			mWeb.onResume();

		// A7 build 138 (round 137 item 1): we are back from the Chrome
		// hand-off - continue the sign-in right here. The friends page
		// loads in this WebView, Android autofill offers the passwords
		// Chrome saved, and applyExtract() closes the screen itself as
		// soon as the Steam ID shows up.
		if( mReturnFromBrowser )
		{
			mReturnFromBrowser = false;
			mSignedInViaBrowser = true;
			Log.i( TAG, "[A7] build138: back from the browser, finishing the Steam sign-in in-app" );
			if( mWeb != null )
				mWeb.loadUrl( URL_FRIENDS );
		}
	}

	@Override
	protected void onDestroy()
	{
		if( mWeb != null )
		{
			mWeb.destroy();
			mWeb = null;
		}
		super.onDestroy();
	}
}