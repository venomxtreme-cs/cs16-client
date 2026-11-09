package su.xash.engine;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;

/**
 * A7 build 147 (round 147 item 5, user: "Steam OpenID sign-in as
 * anti-steal proof" - BOTH PC and Android): the browser does the real
 * Steam login, Steam redirects back to http://127.0.0.1:<port>/ on THIS
 * device, the local listener catches the reply and verifies ownership
 * keylessly with the standard OpenID 2.0 check:
 *   POST https://steamcommunity.com/openid/login
 *   openid.* + openid.mode=check_authentication  ->  is_valid:true
 * NO Steam Web API key (project rule), no WebView screen, no server of
 * ours - a stolen/shared ID cannot pass this, only the account owner
 * who completes the Steam login can.
 *
 * A verified claimed_id (http://steamcommunity.com/openid/id/7656...)
 * goes through the SAME SteamIdConnect.connect() path as the ID row, so
 * the account files and the identity-vault merge (round 147 item 6) are
 * byte-identical no matter which door signed in.
 */
public final class SteamOpenId {
	private static final String TAG = "SteamFriends";

	/** Steam login on a phone can take a while - 5 min then give up */
	private static final int WAIT_MS = 300000;

	private SteamOpenId() {
	}

	/** never blocks the caller: opens the browser now, verifies on a
	 *  worker thread, reports every step through cb on the UI thread. */
	public static void start( final Activity ctx, final File gamedir,
		final SteamIdConnect.Listener cb )
	{
		final ServerSocket listener;
		final int port;
		final String nonce;
		try {
			listener = new ServerSocket();
			listener.bind( new java.net.InetSocketAddress( "127.0.0.1", 0 ));
			listener.setSoTimeout( WAIT_MS );
			port = listener.getLocalPort();
			nonce = newNonce();
		} catch( Throwable t ) {
			report( cb, "Could not open the local sign-in listener: " + t, false );
			return;
		}

		final String ret = "http://127.0.0.1:" + port + "/r?s=" + nonce;
		String auth;
		try {
			auth = "https://steamcommunity.com/openid/login"
				+ "?openid.ns=" + enc( "http://specs.openid.net/auth/2.0" )
				+ "&openid.mode=checkid_setup"
				+ "&openid.identity=" + enc( "http://specs.openid.net/auth/2.0/identifier_select" )
				+ "&openid.claimed_id=" + enc( "http://specs.openid.net/auth/2.0/identifier_select" )
				+ "&openid.return_to=" + enc( ret )
				+ "&openid.realm=" + enc( "http://127.0.0.1:" + port + "/" );
		} catch( Throwable t ) {
			closeQuietly( listener );
			report( cb, "Could not build the Steam sign-in link: " + t, false );
			return;
		}

		if( !openBrowser( ctx, auth, cb )) {
			closeQuietly( listener );
			return;
		}

		Log.i( TAG, "[A7] build147: steam openid sign-in started port " + port );
		post( cb, "Waiting for Steam sign-in in your browser ..." );

		new Thread( new Runnable() {
			@Override
			public void run() {
				runVerify( listener, nonce, gamedir, cb );
			}
		}, "steam-openid" ).start();
	}

	// ------------------------------------------------------------------

	private static void runVerify( ServerSocket listener, String nonce,
		File gamedir, SteamIdConnect.Listener cb )
	{
		Socket client = null;
		try {
			client = listener.accept(); // SoTimeout covers the wait
			client.setSoTimeout( 30000 );
			String query = requestQuery( readRequest( client ));
			respondOk( client );
			closeQuietly( client );
			client = null;
			closeQuietly( listener );

			if( query == null ) {
				report( cb, "Steam sign-in returned no reply - press the button to try again", false );
				return;
			}

			java.util.ArrayList<String> pairs = pairs( query );
			if( !nonce.equals( param( pairs, "s" ))) {
				report( cb, "Steam sign-in reply did not match - press the button to try again", false );
				return;
			}

			String proof = openidBody( pairs );
			if( proof == null ) {
				report( cb, "Steam sign-in carried no proof - press the button to try again", false );
				return;
			}

			post( cb, "Verifying the Steam sign-in proof ..." );
			String resp = postForm( "https://steamcommunity.com/openid/login", proof );
			if( resp == null || !resp.contains( "is_valid:true" )) {
				report( cb, "Steam could not verify the sign-in - press the button to try again", false );
				return;
			}

			// claimed_id arrives percent-encoded - decode before the
			// digit extraction or the %XX codes would pollute the id
			String claimed = param( pairs, "openid.claimed_id" );
			String id = "";
			if( claimed != null ) {
				try {
					claimed = java.net.URLDecoder.decode( claimed, "UTF-8" );
				} catch( Throwable ignored ) {
				}
				id = SteamIdConnect.validate( claimed );
			}
			if( id.isEmpty()) {
				report( cb, "Verified, but the answer carried no Steam ID", false );
				return;
			}

			Log.i( TAG, "[A7] build147: steam openid verified " + id );
			post( cb, "Steam verified - loading profile " + id + " ..." );
			// same door as the ID row: account files + identity vault
			SteamIdConnect.connect( gamedir, id, cb );
		} catch( Throwable t ) {
			report( cb, "Steam sign-in stopped: " + t, false );
		} finally {
			closeQuietly( client );
			closeQuietly( listener );
		}
	}

	/** the browser landed on our listener: parse the GET query */
	private static String requestQuery( String request ) {
		if( request == null || !request.startsWith( "GET " ))
			return null;
		int sp = request.indexOf( ' ', 4 );
		if( sp < 0 )
			return null;
		String target = request.substring( 4, sp );
		int q = target.indexOf( '?' );
		return q >= 0 ? target.substring( q + 1 ) : "";
	}

	private static String readRequest( Socket s ) {
		try {
			InputStream in = s.getInputStream();
			java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
			byte[] chunk = new byte[2048];
			int n;
			while(( n = in.read( chunk )) > 0 ) {
				buf.write( chunk, 0, n );
				String soFar = buf.toString( "ISO-8859-1" );
				if( soFar.contains( "\r\n\r\n" ) || buf.size() > 65536 )
					break;
			}
			return buf.toString( "ISO-8859-1" );
		} catch( Throwable t ) {
			return null;
		}
	}

	private static void respondOk( Socket s ) {
		try {
			String html = "<!doctype html><html><body style=\"background:#14171E;"
				+ "color:#E8ECF4;font-family:sans-serif\">"
				+ "<p>Steam sign-in received. You can close this tab and "
				+ "return to CS16Fresh.</p></body></html>";
			byte[] body = html.getBytes( "UTF-8" );
			String head = "HTTP/1.1 200 OK\r\n"
				+ "Content-Type: text/html; charset=utf-8\r\n"
				+ "Content-Length: " + body.length + "\r\n"
				+ "Connection: close\r\n\r\n";
			OutputStream out = s.getOutputStream();
			out.write( head.getBytes( "ISO-8859-1" ));
			out.write( body );
			out.flush();
		} catch( Throwable ignored ) {
		}
	}

	// ------------------------------------------------------------------
	// query plumbing
	// ------------------------------------------------------------------
	private static java.util.ArrayList<String> pairs( String query ) {
		java.util.ArrayList<String> out = new java.util.ArrayList<String>();
		if( query == null || query.isEmpty())
			return out;
		for( String p : query.split( "&" ))
			if( !p.isEmpty())
				out.add( p );
		return out;
	}

	/** raw (still percent-encoded) value of key= in the pair list */
	private static String param( java.util.ArrayList<String> pairs, String key ) {
		String prefix = key + "=";
		for( String p : pairs )
			if( p.startsWith( prefix ))
				return p.substring( prefix.length() );
		return null;
	}

	/** every openid.* pair with openid.mode swapped for the keyless
	 *  verification mode - values stay as the query carried them, which is
	 *  exactly the application/x-www-form-urlencoded shape the endpoint
	 *  expects (no API key anywhere). */
	private static String openidBody( java.util.ArrayList<String> pairs ) {
		StringBuilder b = new StringBuilder();
		boolean any = false;
		for( String p : pairs ) {
			if( !p.startsWith( "openid." ))
				continue;
			String seg = p.startsWith( "openid.mode=" )
				? "openid.mode=check_authentication" : p;
			if( any )
				b.append( '&' );
			b.append( seg );
			any = true;
		}
		return any ? b.toString() : null;
	}

	private static String postForm( String url, String body ) {
		HttpURLConnection c = null;
		try {
			c = (HttpURLConnection) new URL( url ).openConnection();
			c.setConnectTimeout( 8000 );
			c.setReadTimeout( 10000 );
			c.setRequestMethod( "POST" );
			c.setDoOutput( true );
			c.setRequestProperty( "Content-Type", "application/x-www-form-urlencoded" );
			c.setRequestProperty( "User-Agent", "Mozilla/5.0 (Xash3D Android)" );
			OutputStream out = c.getOutputStream();
			out.write( body.getBytes( "UTF-8" ));
			out.close();

			InputStream in = c.getInputStream();
			java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
			byte[] chunk = new byte[8192];
			int n;
			while(( n = in.read( chunk )) > 0 )
				buf.write( chunk, 0, n );
			in.close();
			return buf.toString( "UTF-8" );
		} catch( Throwable t ) {
			return null;
		} finally {
			if( c != null )
				try { c.disconnect(); } catch( Throwable ignored ) {}
		}
	}

	// ------------------------------------------------------------------
	// browser + reporting
	// ------------------------------------------------------------------
	/** Chrome first (its saved passwords make the login one tap), any
	 *  other browser as the fallback - same hand-off the sign-in screen
	 *  uses. Returns false only when NO browser could be opened. */
	private static boolean openBrowser( Activity ctx, String url,
		SteamIdConnect.Listener cb )
	{
		try {
			Intent chrome = new Intent( Intent.ACTION_VIEW, Uri.parse( url ));
			chrome.setPackage( "com.android.chrome" );
			ctx.startActivity( chrome );
			Log.i( TAG, "[A7] build147: steam openid handed to Chrome" );
			return true;
		} catch( Throwable ignored ) {
		}
		try {
			Intent any = new Intent( Intent.ACTION_VIEW, Uri.parse( url ));
			ctx.startActivity( any );
			Log.i( TAG, "[A7] build147: steam openid handed to the browser" );
			return true;
		} catch( Throwable t ) {
			report( cb, "No browser available to complete the Steam sign-in", false );
			return false;
		}
	}

	private static void post( final SteamIdConnect.Listener cb, final String status ) {
		if( cb == null )
			return;
		Handler h = new Handler( Looper.getMainLooper());
		h.post( new Runnable() {
			@Override
			public void run() {
				cb.onStatus( status );
			}
		});
	}

	/** status + onDone(false) on the UI thread (never onConnected) */
	private static void report( final SteamIdConnect.Listener cb,
		final String status, final boolean ok )
	{
		if( cb == null )
			return;
		Handler h = new Handler( Looper.getMainLooper());
		h.post( new Runnable() {
			@Override
			public void run() {
				cb.onStatus( status );
				cb.onDone( ok );
			}
		});
	}

	// ------------------------------------------------------------------

	private static String enc( String s ) {
		try {
			return java.net.URLEncoder.encode( s, "UTF-8" );
		} catch( Throwable t ) {
			return s;
		}
	}

	private static String newNonce() {
		try {
			java.security.SecureRandom r = new java.security.SecureRandom();
			byte[] b = new byte[16];
			r.nextBytes( b );
			StringBuilder sb = new StringBuilder( 32 );
			for( byte x : b )
				sb.append( String.format( "%02x", x & 0xFF ));
			return sb.toString();
		} catch( Throwable t ) {
			return Long.toHexString( System.currentTimeMillis());
		}
	}

	private static void closeQuietly( java.io.Closeable c ) {
		if( c == null )
			return;
		try { c.close(); } catch( Throwable ignored ) {}
	}

	private static void closeQuietly( Socket s ) {
		if( s == null )
			return;
		try { s.close(); } catch( Throwable ignored ) {}
	}

	private static void closeQuietly( ServerSocket s ) {
		if( s == null )
			return;
		try { s.close(); } catch( Throwable ignored ) {}
	}
}
