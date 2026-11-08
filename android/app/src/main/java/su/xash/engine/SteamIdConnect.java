package su.xash.engine;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;

/**
 * A7 build 146 (round 146 item 2, user: Android launcher Steam
 * connect-by-ID "without browser"): the connect-by-ID flow that lives in
 * SteamFriendsActivity, lifted into a WEBVIEWLESS helper the LAUNCHER can
 * run - settings card -> type a Steam ID -> Connect, with no Chrome
 * hand-off, no WebView screen and NO Steam Web API key (project rule).
 *
 * What one ID produces, byte-for-byte same files as the existing screen:
 *   <gamedir>/friends/steamid.txt     the steamid64
 *   <gamedir>/friends/name.txt        sanitized persona (fine one)
 *   <gamedir>/media/avatars/avatar_self.png  profile photo (opaque 64px)
 *   <gamedir>/friends/names.txt       friends roster (keyless fetch)
 *   <gamedir>/friends/servers.txt     "ip:port<TAB>name" + name-only rows
 *   <gamedir>/friends/avatars/fa_*.png + avatars.txt
 * the same file set the Chrome flow and the ID row of the friends screen
 * write, so the launcher card, the in-game Options Steam row, the menu
 * persona signal (notifyAvatarSaved) and Find Servers -> Friends all keep
 * working unchanged from either door.
 *
 * The profile XML endpoint (steamcommunity.com/profiles/<id>/?xml=1) and
 * the playerdb.co mirror are the ones SteamFriendsActivity already uses.
 *
 * NOTE for tree readers: SteamFriendsActivity keeps its own copy of this
 * logic - it must stay reachable on its own (the screen is also opened
 * from the ENGINE's "steamfriends" command while the game runs), and this
 * repo already carries knowingly parallel copies of Steam glue (the engine
 * manifest twin). The two were kept wording-identical on purpose so the
 * log markers do not split between them.
 */
public final class SteamIdConnect {
	private static final String TAG = "SteamFriends";

	public interface Listener {
		/** runs on the UI thread; status is ready to be shown verbatim. */
		void onStatus( String status );
		/** runs on the UI thread after a SUCCESSFUL connect. */
		void onConnected( String steamId64, String persona, boolean changed );
		/** runs on the UI thread when the attempt is OVER - ok=true only
		 *  after a successful connect, so a failed lookup unblocks the
		 *  input too. Not called for the mid-flight "Looking up" line. */
		void onDone( boolean ok );
	}

	private SteamIdConnect() {
	}

	// ------------------------------------------------------------------
	// validation
	// ------------------------------------------------------------------
	/** user input to a steamid64: a plain 7656... 17-digit number (any
	 *  junk around it is stripped - a pasted profile URL works too) or the
	 *  legacy STEAM_X:Y:Z form. Returns "" when nothing usable. */
	public static String normalize( String raw )
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

		return s.replaceAll( "[^0-9]", "" );
	}

	/** the SteamID3 form ([U:1:xxx]) - the same account id offset the
	 *  legacy form uses. Empty when the input is not a SteamID3. */
	private static String fromSteamId3( String s )
	{
		java.util.regex.Matcher m = java.util.regex.Pattern.compile(
			"\\[U:1:([0-9]+)\\]", java.util.regex.Pattern.CASE_INSENSITIVE ).matcher( s );
		if( m.find())
		{
			try {
				long z = Long.parseLong( m.group( 1 ));
				return String.valueOf( 76561197960265729L + z );
			} catch( Throwable ignored ) {
			}
		}
		return "";
	}

	/** validate raw input -> steamid64 or "" (both the plain number, the
	 *  legacy STEAM_X:Y:Z and the SteamID3 [U:1:xxx] forms are accepted). */
	public static String validate( String raw )
	{
		if( raw == null )
			return "";
		String s3 = fromSteamId3( raw );
		if( !s3.isEmpty())
			return s3;
		String id = normalize( raw );
		return id.matches( "7656[0-9]{13}" ) ? id : "";
	}

	// ------------------------------------------------------------------
	// the connect itself
	// ------------------------------------------------------------------
	/** validate, look the profile up off the UI thread, write the account
	 *  files (+ the keyless friends roster), signal the running menu and
	 *  report back on the UI thread. Never blocks the caller. */
	public static void connect( final File gamedir, final String raw, final Listener cb )
	{
		final String id = validate( raw );
		if( id.isEmpty())
		{
			report( cb, "Not a Steam ID - enter 7656... (17 digits), STEAM_0:0:1 or [U:1:xxx]", null, "", false, false, true );
			return;
		}

		report( cb, "Looking up Steam ID " + id + " ...", null, "", false, false, false );
		Log.i( TAG, "[A7] build146: launcher steam connect-by-id started " + id );

		new Thread( new Runnable() {
			@Override
			public void run() {
				runLookup( gamedir, id, cb );
			}
		}, "steam-id-connect" ).start();
	}

	private static void runLookup( File gamedir, String id, Listener cb )
	{
		String xml = httpGetString( "https://steamcommunity.com/profiles/" + id + "/?xml=1" );

		String name = null, avatar = null;
		boolean profileSeen = false;
		// Private / friends-only: the ID is valid but Steam hides the
		// persona photo - save the id and say so instead of a failure
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

		// steamcommunity blocked/smells like a bot -> the playerdb mirror
		if( name == null || name.isEmpty())
		{
			String json = httpGetString( "https://playerdb.co/api/player/steam/" + id );
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
			report( cb, "No answer from Steam - check internet, tap Connect again", null, id, false, false, true );
			return;
		}
		if( !profileSeen && avatar == null )
		{
			report( cb, "Steam ID " + id + " not found - check the number", null, id, false, false, true );
			return;
		}

		File friendsDir = new File( gamedir, "friends" );
		boolean changed = false;
		String currentId = readTextFile( new File( friendsDir, "steamid.txt" ));

		if( !id.equals( currentId ) || !new File( friendsDir, "steamid.txt" ).isFile())
		{
			writeTextFile( new File( friendsDir, "steamid.txt" ), id );
			changed = true;
		}

		String clean = null;
		if( !hiddenProfile && name != null && !name.isEmpty())
		{
			String sanitized = sanitizePersona( name );
			if( !sanitized.isEmpty() && !sanitized.equals( readTextFile( new File( friendsDir, "name.txt" ))))
			{
				writeTextFile( new File( friendsDir, "name.txt" ), sanitized );
				changed = true;
				clean = sanitized;
			}
		}

		if( !hiddenProfile )
			saveOwnAvatar( gamedir, id, avatar != null ? avatar : "" );

		// keyless friends roster - the same one the friends screen refreshes
		// (names.txt / servers.txt / friends/avatars) with "no session needed"
		refreshFriendsKeyless( gamedir, id );

		if( changed )
		{
			// build 141 marker kept EXACTLY - scan112 already watches it and
			// the launcher door now produces the same DEX string
			Log.i( TAG, "[A7] build141: steam connected via ID entry " + id );
			XashActivity.notifyAvatarSaved();
		}

		String status;
		if( hiddenProfile )
			status = "Steam ID: " + id + " - profile is private, set it to Public for name/photo";
		else if( clean != null )
			status = "Connected: " + clean + "  -  Steam ID " + id;
		else if( name != null && !name.isEmpty())
			status = "Connected: " + sanitizePersona( name ) + "  -  Steam ID " + id;
		else
			status = "Steam ID: " + id + " - no name returned";

		report( cb, status, clean, id, true, changed, true );
	}

	/** status first (progress lines), success last (one UI hand-off);
	 *  done=false marks the mid-flight "looking up" line only. */
	private static void report( final Listener cb, final String status,
		final String persona, final String id, final boolean ok,
		final boolean changed, final boolean done )
	{
		if( cb == null )
			return;
		// run on the UI thread: the callers are launcher buttons
		android.os.Handler h;
		try {
			h = new android.os.Handler( android.os.Looper.getMainLooper());
		} catch( Throwable t ) {
			cb.onStatus( status );
			if( ok )
				cb.onConnected( id, persona, changed );
			if( done )
				cb.onDone( ok );
			return;
		}
		h.post( new Runnable() {
			@Override
			public void run() {
				cb.onStatus( status );
				if( ok )
					cb.onConnected( id, persona, changed );
				if( done )
					cb.onDone( ok );
			}
		});
	}

	// ------------------------------------------------------------------
	// keyless friends roster (mirrors SteamFriendsActivity.refreshFriendsKeyless
	// minus the WebView half - it only writes, never renders)
	// ------------------------------------------------------------------
	private static void refreshFriendsKeyless( File gamedir, String sid )
	{
		File friendsDir = new File( gamedir, "friends" );

		String html = httpGetString( "https://steamcommunity.com/profiles/" + sid + "/friends/" );
		if( html == null || html.isEmpty() || html.contains( "This user's profile is private" ))
			return; // network / private profile - saved lists stay untouched

		java.util.ArrayList<String[]> friends = parseFriendsPage( html );
		for( int i = friends.size() - 1; i >= 0; i-- )
			if( friends.get( i )[0].equals( sid ))
				friends.remove( i );

		java.util.ArrayList<String> names = new java.util.ArrayList<String>();
		java.util.ArrayList<String[]> rows = new java.util.ArrayList<String[]>();
		java.util.ArrayList<String> joinable = new java.util.ArrayList<String>();

		// real join links keep their address, paired with the friend whose
		// entry sits closest ABOVE the match (the link lives in that row)
		java.util.regex.Matcher sm = java.util.regex.Pattern.compile(
			"steam://connect/([^\"'<>\\s\\\\]+)" ).matcher( html );
		while( sm.find())
		{
			String addr = sm.group( 1 ).replace( "&" + "amp;", "&" )
				.replaceFirst( "[),.;]+$", "" );
			if( addr.indexOf( ':' ) < 0 )
				continue;

			String[] near = null;
			for( String[] fr : friends )
			{
				// entries carry their byte position in field[2]
				int pos;
				try {
					pos = Integer.parseInt( fr[2] );
				} catch( Throwable t ) {
					pos = -1;
				}
				if( pos < 0 || pos >= sm.start()) continue;
				if( near == null || Integer.parseInt( near[2] ) < pos )
					near = fr;
			}

			String nm = ( near != null ) ? near[1] : "";
			rows.add( new String[]{ addr, nm.isEmpty() ? addr : nm });
			if( near != null )
				joinable.add( near[1] );
		}

		for( String[] fr : friends )
		{
			names.add( fr[1] );
			// friends with nowhere to join still land as name-only rows
			if( !joinable.contains( fr[1] ))
				rows.add( new String[]{ "", fr[1] });
		}

		// never overwrite a good list with an empty one
		if( !names.isEmpty())
			writeLines( new File( friendsDir, "names.txt" ), names );
		if( !rows.isEmpty())
			writeRows( new File( friendsDir, "servers.txt" ), rows );

		saveFriendAvatars( gamedir, friends );

		Log.i( TAG, "[A7] build146: launcher keyless friends refresh - "
			+ names.size() + " names, " + rows.size() + " rows" );
	}

	/** steamcommunity friends page (public, no session) -> rows of
	 *  {id, name, position}: embedded JSON entities first, plain profile
	 *  anchors as the fallback markup. */
	private static java.util.ArrayList<String[]> parseFriendsPage( String html )
	{
		java.util.ArrayList<String[]> out = new java.util.ArrayList<String[]>();
		java.util.regex.Pattern idPat = java.util.regex.Pattern.compile(
			"\"steamid\"\\s*:\\s*\"(7656[0-9]{13})\"" );
		java.util.regex.Pattern namePat = java.util.regex.Pattern.compile(
			"\"personaname\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"" );

		java.util.regex.Matcher m = idPat.matcher( html );
		while( m.find())
		{
			int from = Math.max( 0, m.start() - 600 );
			int to = Math.min( html.length(), m.end() + 1400 );
			String nm = "";
			java.util.regex.Matcher n = namePat.matcher( html );
			n.region( from, to );
			if( n.find())
				nm = sanitizePersona( n.group( 1 ).replace( "\\\"", "\"" ).replace( "\\\\", "\\" ).replace( "\\/", "/" ));
			if( !nm.isEmpty())
				out.add( new String[]{ m.group( 1 ), nm, String.valueOf( m.start()) });
		}

		if( out.isEmpty())
		{
			java.util.regex.Matcher a = java.util.regex.Pattern.compile(
				"profiles/(7656[0-9]{13})[^>]*>\\s*([^<]{1,64})\\s*<" ).matcher( html );
			while( a.find())
			{
				String nm = sanitizePersona( a.group( 2 ).trim());
				if( !nm.isEmpty())
					out.add( new String[]{ a.group( 1 ), nm, String.valueOf( a.start()) });
			}
		}

		return out;
	}

	// ------------------------------------------------------------------
	// file helpers
	// ------------------------------------------------------------------
	/** persona -> something the name cvar and userinfo accept - no
	 *  quotes/backslashes/semicolon, no control chars, 31 chars (build 139). */
	public static String sanitizePersona( String s )
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

	private static String readTextFile( File f )
	{
		try {
			if( !f.isFile())
				return "";
			java.io.BufferedReader r = new java.io.BufferedReader(
				new java.io.InputStreamReader( new java.io.FileInputStream( f ), "UTF-8" ));
			String line = r.readLine();
			r.close();
			return line != null ? line.trim() : "";
		} catch( Throwable t ) {
			return "";
		}
	}

	public static void writeTextFile( File f, String text )
	{
		try {
			File parent = f.getParentFile();
			if( parent != null )
				parent.mkdirs();
			OutputStreamWriter w = new OutputStreamWriter(
				new FileOutputStream( f, false ), "UTF-8" );
			w.write( text + "\n" );
			w.close();
		} catch( Throwable t ) {
			// external storage missing - nothing to write through
		}
	}

	private static void writeLines( File f, java.util.ArrayList<String> lines )
	{
		try {
			File parent = f.getParentFile();
			if( parent != null )
				parent.mkdirs();
			OutputStreamWriter w = new OutputStreamWriter(
				new FileOutputStream( f, false ), "UTF-8" );
			for( String s : lines )
				w.write( s + "\n" );
			w.close();
		} catch( Throwable t ) {
		}
	}

	/** "ip:port<TAB>name" per row - empty first field = name-only row. */
	private static void writeRows( File f, java.util.ArrayList<String[]> rows )
	{
		try {
			File parent = f.getParentFile();
			if( parent != null )
				parent.mkdirs();
			OutputStreamWriter w = new OutputStreamWriter(
				new FileOutputStream( f, false ), "UTF-8" );
			for( String[] r : rows )
			{
				String addr = r[0];
				String nm = r[1];
				w.write( addr + "\t" + ( nm.isEmpty() ? addr : nm ) + "\n" );
			}
			w.close();
		} catch( Throwable t ) {
		}
	}

	// ------------------------------------------------------------------
	// photos
	// ------------------------------------------------------------------
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

	/** centre square-crop + scale: the row plate is small. */
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

	/** my own photo -> <gamedir>/media/avatars/avatar_self.png, OPAQUE 64px
	 *  (a transparent one lights up the menu's white preview plate - build 143). */
	private static void saveOwnAvatar( File gamedir, String sid, String selfUrl )
	{
		try {
			String url = normalizeAvatarUrl( selfUrl );
			if( url.isEmpty() && !sid.isEmpty())
			{
				String xml = httpGetString( "https://steamcommunity.com/profiles/" + sid + "/?xml=1" );
				if( xml != null )
					url = normalizeAvatarUrl( extractXmlTag( xml, "avatarFull" ));
			}
			if( url.isEmpty())
				return;

			byte[] img = httpGet( url );
			if( img == null || img.length < 64 )
				return;

			android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeByteArray( img, 0, img.length );
			if( bmp == null )
				return;
			android.graphics.Bitmap sq = squareCrop( bmp, 64 );
			bmp.recycle();

			if( sq.hasAlpha())
			{
				android.graphics.Bitmap flat = android.graphics.Bitmap.createBitmap(
					sq.getWidth(), sq.getHeight(), android.graphics.Bitmap.Config.ARGB_8888 );
				android.graphics.Canvas cv = new android.graphics.Canvas( flat );
				cv.drawColor( 0xFFFFFFFF );
				cv.drawBitmap( sq, 0, 0, null );
				if( sq != bmp )
					sq.recycle();
				sq = flat;
			}

			File dir = new File( gamedir, "media/avatars" );
			if( !dir.isDirectory() && !dir.mkdirs())
			{
				sq.recycle();
				return;
			}
			FileOutputStream fos = new FileOutputStream( new File( dir, "avatar_self.png" ));
			sq.compress( android.graphics.Bitmap.CompressFormat.PNG, 90, fos );
			fos.close();
			sq.recycle();
		} catch( Throwable t ) {
			Log.w( TAG, "own avatar save failed", t );
		}
	}

	/** each friend's photo -> <gamedir>/friends/avatars/fa_<n>.png plus a
	 *  "name<TAB>relpath" map (avatars.txt), one shared write at the end. */
	private static void saveFriendAvatars( File gamedir, java.util.ArrayList<String[]> friends )
	{
		File friendsDir = new File( gamedir, "friends" );
		File dir = new File( friendsDir, "avatars" );

		StringBuilder map = new StringBuilder();
		int ok = 0;
		try {
			for( String[] fr : friends )
			{
				if( ok >= 32 )
					break; // roster refresh is a bonus - keep it cheap
				String url = friendAvatarUrl( fr[0] );
				if( url.isEmpty())
					continue;

				byte[] img = httpGet( url );
				if( img == null || img.length < 64 )
					continue;
				android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeByteArray( img, 0, img.length );
				if( bmp == null )
					continue;
				android.graphics.Bitmap sq = squareCrop( bmp, 64 );
				bmp.recycle();

				if( !dir.isDirectory() && !dir.mkdirs())
				{
					sq.recycle();
					continue;
				}
				FileOutputStream fos = new FileOutputStream( new File( dir, "fa_" + ok + ".png" ));
				sq.compress( android.graphics.Bitmap.CompressFormat.PNG, 90, fos );
				fos.close();
				sq.recycle();

				map.append( fr[1] ).append( '\t' ).append( "friends/avatars/fa_" ).append( ok )
					.append( ".png" ).append( '\n' );
				ok++;
			}
		} catch( Throwable t ) {
			Log.w( TAG, "friend avatars failed", t );
		}

		// trim keeps avatars.txt shaped exactly like the screen's: one
		// "name<TAB>relpath" row per line, no extra blank line at the end
		if( ok > 0 )
			writeTextFile( new File( friendsDir, "avatars.txt" ), map.toString().trim());
	}

	/** the avatar url of the profile block closest to <pos> in a page we
	 *  do not keep around any more: re-fetch is cheaper than storing the
	 *  whole page for the launcher path, and it only runs for the capped
	 *  32 friends. */
	private static String friendAvatarUrl( String id )
	{
		String xml = httpGetString( "https://steamcommunity.com/profiles/" + id + "/?xml=1" );
		if( xml == null )
			return "";
		return normalizeAvatarUrl( extractXmlTag( xml, "avatarFull" ));
	}

	// ------------------------------------------------------------------
	// tiny network + parse primitives (identical to the screen's copies)
	// ------------------------------------------------------------------
	public static byte[] httpGet( String url )
	{
		java.net.HttpURLConnection c = null;
		try {
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
		} catch( Throwable t ) {
			return null;
		} finally {
			if( c != null )
				try { c.disconnect(); } catch( Throwable ignored ) {}
		}
	}

	public static String httpGetString( String url )
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
	public static String extractXmlTag( String xml, String tag )
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

	/** first "field":"value" of a small JSON document (playerdb mirror) */
	public static String jsonStringField( String json, String field )
	{
		java.util.regex.Matcher m = java.util.regex.Pattern.compile(
			"\"" + field + "\"\\s*:\\s*\"([^\"]+)\"" ).matcher( json );
		return m.find() ? m.group( 1 ) : null;
	}
}