package su.xash.engine;

import android.util.Log;

import java.io.File;

/**
 * A7 build 147 (round 147 item 6, user: "Identity into game nvault ...
 * merged with existing CS 1.6 save data, never wiped"): merges the
 * signed-in Steam identity into the game's own AMX Mod X nvault store at
 *   <gamedir>/addons/amxmodx/data/vault/identity.vault
 * which is exactly what nvault_open("identity") resolves
 * (amxmodx-1.9/modules/nvault/amxxapi.cpp: MF_BuildPathnameR("%s/vault",
 * amxx_datadir) + "/<name>.vault").
 *
 * Binary layout is AMXX NVault.cpp, little endian on every target we
 * ship: u32 magic 0x6E564C54, u16 version 0x0200, u32 entry count, then
 * per entry: i32 stamp, u8 keylen, u16 vallen, key bytes, value bytes.
 *
 * Merge semantics - the "never wiped" rule:
 *   - every existing entry is read back and rewritten as-is (stamps
 *     included), so plugin save data in the file survives untouched;
 *   - the identity keys steamid64 / steamid / persona are added or
 *     updated in place, stamp = now only for the keys we changed;
 *   - a missing persona (private profile) never erases a stored one;
 *   - a file whose header is not an nvault is left completely alone
 *     (no overwrite, no data loss);
 *   - the rewrite goes through identity.vault.tmp + rename, so a crash
 *     mid-write can never truncate the vault.
 */
public final class IdentityVault {
	private static final String TAG = "SteamFriends";

	private static final int VAULT_MAGIC = 0x6E564C54;
	private static final int VAULT_VERSION = 0x0200;

	private IdentityVault() {
	}

	/** merge whatever identity is on disk right now (friends/steamid.txt
	 *  + friends/name.txt) into the vault. Silent no-op when not signed in. */
	public static void write( File gamedir ) {
		try {
			String id = firstLine( new File( gamedir, "friends/steamid.txt" ));
			if( id.isEmpty())
				return;

			String persona = firstLine( new File( gamedir, "friends/name.txt" ));
			merge( new File( gamedir, "addons/amxmodx/data/vault/identity.vault" ),
				id, persona );
		} catch( Throwable t ) {
			Log.w( TAG, "[A7] build147: identity vault merge failed: " + t );
		}
	}

	// ------------------------------------------------------------------

	private static void merge( File vault, String id, String persona ) {
		java.util.ArrayList<byte[]> keys = new java.util.ArrayList<byte[]>();
		java.util.ArrayList<byte[]> vals = new java.util.ArrayList<byte[]>();
		java.util.ArrayList<Integer> stamps = new java.util.ArrayList<Integer>();

		boolean existed = vault.isFile();
		if( existed ) {
			byte[] old = readAll( vault );
			if( old == null || !parse( old, keys, vals, stamps ))
				return; // unreadable or not an nvault - NEVER overwrite
		}

		int now = (int)( System.currentTimeMillis() / 1000L );
		int changed = 0;
		changed += put( keys, vals, stamps, "steamid64", id, now );
		changed += put( keys, vals, stamps, "steamid", id, now );
		if( !persona.isEmpty())
			changed += put( keys, vals, stamps, "persona", persona, now );

		if( changed == 0 && existed )
			return; // identical - no rewrite, no mtime churn

		File dir = vault.getParentFile();
		if( dir != null && !dir.isDirectory() && !dir.mkdirs())
			return;

		File tmp = new File( dir, "identity.vault.tmp" );
		byte[] out = build( keys, vals, stamps );
		if( out == null )
			return;
		try {
			java.io.FileOutputStream fos = new java.io.FileOutputStream( tmp );
			fos.write( out );
			fos.close();
		} catch( Throwable t ) {
			Log.w( TAG, "[A7] build147: identity vault tmp write failed: " + t );
			tmp.delete();
			return;
		}

		if( !tmp.renameTo( vault )) {
			// POSIX rename replaces in place; a stubborn FS gets the
			// delete-then-rename fallback (tmp still holds full content)
			vault.delete();
			if( !tmp.renameTo( vault )) {
				tmp.delete();
				return;
			}
		}

		Log.i( TAG, "[A7] build147: identity vault merged - "
			+ keys.size() + " entries, id " + id );
	}

	/** @return bytes added/updated (0 = key already had this value) */
	private static int put( java.util.ArrayList<byte[]> keys,
		java.util.ArrayList<byte[]> vals, java.util.ArrayList<Integer> stamps,
		String key, String value, int now )
	{
		byte[] k = utf8( key );
		byte[] v = utf8( value );
		if( v.length > 0xFFFF )
			return 0; // nvault value field is u16 - cannot store it

		for( int i = 0; i < keys.size(); i++ ) {
			if( !java.util.Arrays.equals( keys.get( i ), k ))
				continue;
			byte[] old = vals.get( i );
			if( java.util.Arrays.equals( old, v ))
				return 0;
			vals.set( i, v );
			stamps.set( i, now );
			return 1;
		}

		if( k.length > 255 || keys.size() >= 4096 )
			return 0; // key field is u8 - skip rather than corrupt
		keys.add( k );
		vals.add( v );
		stamps.add( now );
		return 1;
	}

	/** strict parse - false on ANY truncation or foreign header */
	private static boolean parse( byte[] b, java.util.ArrayList<byte[]> keys,
		java.util.ArrayList<byte[]> vals, java.util.ArrayList<Integer> stamps )
	{
		if( b.length == 0 )
			return true; // empty file = empty vault (AMXX tolerates it too)
		if( b.length < 10 )
			return false;
		if( rd32( b, 0 ) != VAULT_MAGIC || rd16( b, 4 ) != VAULT_VERSION )
			return false;

		int count = rd32( b, 6 );
		if( count < 0 || count > 1000000 )
			return false;

		int off = 10;
		for( int i = 0; i < count; i++ ) {
			if( off + 7 > b.length )
				return false;
			int stamp = rd32( b, off ); off += 4;
			int keylen = b[off++] & 0xFF;
			int vallen = rd16( b, off ); off += 2;
			if( off + keylen + vallen > b.length )
				return false;

			byte[] k = new byte[keylen];
			System.arraycopy( b, off, k, 0, keylen ); off += keylen;
			byte[] v = new byte[vallen];
			System.arraycopy( b, off, v, 0, vallen ); off += vallen;

			keys.add( k );
			vals.add( v );
			stamps.add( stamp );
		}
		return true;
	}

	private static byte[] build( java.util.ArrayList<byte[]> keys,
		java.util.ArrayList<byte[]> vals, java.util.ArrayList<Integer> stamps )
	{
		try {
			java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
			wr32( o, VAULT_MAGIC );
			wr16( o, VAULT_VERSION );
			wr32( o, keys.size() );
			for( int i = 0; i < keys.size(); i++ ) {
				byte[] k = keys.get( i );
				byte[] v = vals.get( i );
				if( k.length > 255 || v.length > 0xFFFF )
					return null;
				wr32( o, stamps.get( i ));
				o.write( k.length & 0xFF );
				wr16( o, v.length );
				o.write( k );
				o.write( v );
			}
			return o.toByteArray();
		} catch( Throwable t ) {
			return null;
		}
	}

	// ------------------------------------------------------------------
	// byte plumbing (little endian - matches Binary.cpp raw fwrite)
	// ------------------------------------------------------------------
	private static int rd32( byte[] b, int off ) {
		return ( b[off] & 0xFF ) | ( ( b[off + 1] & 0xFF ) << 8 )
			| ( ( b[off + 2] & 0xFF ) << 16 ) | ( ( b[off + 3] & 0xFF ) << 24 );
	}

	private static int rd16( byte[] b, int off ) {
		return ( b[off] & 0xFF ) | ( ( b[off + 1] & 0xFF ) << 8 );
	}

	private static void wr32( java.io.ByteArrayOutputStream o, int n ) {
		o.write( n & 0xFF ); o.write( ( n >>> 8 ) & 0xFF );
		o.write( ( n >>> 16 ) & 0xFF ); o.write( ( n >>> 24 ) & 0xFF );
	}

	private static void wr16( java.io.ByteArrayOutputStream o, int n ) {
		o.write( n & 0xFF ); o.write( ( n >>> 8 ) & 0xFF );
	}

	private static byte[] utf8( String s ) {
		try {
			return s.getBytes( "UTF-8" );
		} catch( Throwable t ) {
			return new byte[0];
		}
	}

	private static byte[] readAll( File f ) {
		try {
			java.io.FileInputStream in = new java.io.FileInputStream( f );
			try {
				java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
				byte[] chunk = new byte[4096];
				int n;
				while(( n = in.read( chunk )) > 0 )
					out.write( chunk, 0, n );
				return out.toByteArray();
			} finally {
				in.close();
			}
		} catch( Throwable t ) {
			return null;
		}
	}

	private static String firstLine( File f ) {
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
}
