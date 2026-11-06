/***
*
*	Copyright (c) 1999, Valve LLC. All rights reserved.
*
*	This product contains software technology licensed from Id
*	Software, Inc. ("Id Technology").  Id Technology (c) 1996 Id Software, Inc.
*	All Rights Reserved.
*
*   Use, distribution, and modification of this source code and/or resulting
*   object code is restricted to non-commercial enhancements to products from
*   Valve LLC.  All other use, distribution, or modification is prohibited
*   without written permission from Valve LLC.
*
****/
//
// Scoreboard.cpp
//
// implementation of CHudScoreboard class
//
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <ctype.h>
#include "hud.h"
#include "cl_util.h"
#include "parsemsg.h"
#include "triangleapi.h"
#include "com_weapons.h"
#include "cdll_dll.h"
#include "draw_util.h"
#include "vgui_parser.h"
#include "eventscripts.h"

hud_player_info_t   g_PlayerInfoList[MAX_PLAYERS+1]; // player info from the engine
extra_player_info_t	g_PlayerExtraInfo[MAX_PLAYERS+1]; // additional player info sent directly to the client dll
team_info_t         g_TeamInfo[MAX_TEAMS+1];
hostage_info_t      g_HostageInfo[MAX_HOSTAGES+1];
int g_iUser1;
int g_iUser2;
int g_iUser3;
int g_iTeamNumber;


// X positions

int xstart, xend;
int ystart, yend;

enum
{
	COL_NAME = 0,
	COL_ATTRIB,
	COL_HP,
	COL_MONEY,
	COL_KILLS,
	COL_DEATHS,
	COL_PING,
	TOTAL_COLUMNS
};

static struct Column
{
	int start, end;
	const char *name;

	Column() :
	    start( 0 ), end( 0 ), name( nullptr ) { }

	Column( int s, const char *n = nullptr, bool reverse = true )
	{
		name = n;
		end = 0;
		start = 0;

		if ( reverse )
		{
			start = s;
			if ( n )
				end = start - DrawUtils::HudStringLen( n );
		}
		else
		{
			start = s;
			if ( n )
				end = start + DrawUtils::HudStringLen( n );
		}
	}
} g_Columns[TOTAL_COLUMNS];

// Y positions - declared up here so the logo column below can use the
// row pitch for its plate size
// build 101 gave the plate its own row space ("logo looks too small"),
// build 102 took it to 24 px ("still small") and build 103 goes again
// with a PC scoreboard reference picture in hand: the plate holds ~90% of
// the row height, which is what the alphas look like there - a 32 px
// picture in a 36 px row (stock was 13 in 15). Every row draw below
// derives from ROW_GAP and both list loops stop at yend, so taller rows
// only mean fewer visible lines on short screens - never overlap.
#define ROW_GAP  36

// the logo plate itself: a square inside the row, picture + a thin frame
// like the PC board's player plates (was 17 in 19 (102), 13 in 15 stock)
#define LOGO_SIZE  32
#define LOGO_BOX_X  2   // plate's left margin from the frame edge
// total room the plate takes out of the name column: margin + picture +
// a 6px gap, so the names line up with the header text
#define LOGO_GUTTER ( LOGO_BOX_X + LOGO_SIZE + 6 )

// the logo chain further down is file-static, so the two values it needs
// are cached here instead of being reached through the class (declared
// before Init, which publishes cl_playerlogo into them)
static cvar_t *s_cl_playerlogo = NULL;
static int s_localSlot = 0;

//#include "vgui_TeamFortressViewport.h"

int CHudScoreboard :: Init( void )
{
	gHUD.AddHudElem( this );

	// Hook messages & commands here
	HOOK_COMMAND( gHUD.m_Scoreboard, "+showscores", ShowScores );
	HOOK_COMMAND( gHUD.m_Scoreboard, "-showscores", HideScores );
	HOOK_COMMAND( gHUD.m_Scoreboard, "showscoreboard2", ShowScoreboard2 );
	HOOK_COMMAND( gHUD.m_Scoreboard, "hidescoreboard2", HideScoreboard2 );

	HOOK_MESSAGE( gHUD.m_Scoreboard, ScoreInfo );
	HOOK_MESSAGE( gHUD.m_Scoreboard, TeamScore );
	HOOK_MESSAGE( gHUD.m_Scoreboard, TeamInfo );

	InitHUDData();

	cl_showpacketloss = CVAR_CREATE( "cl_showpacketloss", "0", FCVAR_ARCHIVE );
	cl_showplayerversion = CVAR_CREATE( "cl_showplayerversion", "0", 0 );
	cl_show_scoreboard_on_death = CVAR_CREATE( "cl_show_scoreboard_on_death", "0", FCVAR_ARCHIVE );

	// A7 round 100: the menu no longer PICKS the scoreboard logo - the
	// chain above resolves it. cl_playerlogo stays registered (and
	// FCVAR_ARCHIVE) as the logos/ fallback the menu fills in and as the
	// stored value of any older pick.
	cl_playerlogo = CVAR_CREATE( "cl_playerlogo", "", FCVAR_ARCHIVE );
	s_cl_playerlogo = cl_playerlogo;
	m_iNumFriends = 0;

	return 1;
}

// ===========================================================================
// A7 round 100 - automatic scoreboard logos
// (user: "logo ... just import image or connect from steam, not choosing")
//
// Every picture is a file on disk and the draw only reads files:
//
//   my row    media/avatars/avatar_custom.png   "Import image..." (Options)
//             media/avatars/avatar_self.png     my Steam photo, saved by the
//                                                Steam friends screen
//             media/avatars/av_<account>.png    my *sid sent by reunion/
//                                                dproto/goldsource servers
//             cl_playerlogo                     the menu's logos/ fallback
//
//   any row   friends/avatars.txt               a Steam friend, name -> file
//             media/avatars/av_<account>.png    that player's *sid photo
//
// A picture that isn't there YET is not remembered as a failure the way the
// old logo path did (that is why it vanished on some servers): a miss is
// re-probed every AVATAR_PROBE_TIME seconds while the scoreboard is drawn,
// so a fetch that lands mid-server, an image imported mid-game and a
// vid_restart all heal on their own. The path is drawn on every server, xash
// and goldsource alike - nothing about it is server-side.
// ===========================================================================
#define AVATAR_PROBE_TIME 2.0f
#define MAX_SB_AVAMAP     48     // name -> picture rows in friends/avatars.txt
#define MAX_AVA_PATH      128

static struct
{
	char path[MAX_AVA_PATH];
	int  tex;      // 0 = not tried yet, -1 = no picture on disk, >0 = texture
	float next;    // gHUD.m_flTime when this entry may be probed again
} s_avas[64];

static struct
{
	char path[MAX_AVA_PATH];   // resolved for this slot ("" = nothing known)
	float next;                // next re-resolve time while nothing is found
} s_slotPath[MAX_PLAYERS + 1];

// my Steam account number, read once from the friends screen's
// accounts file (steamid64 minus the Steam universe base)
static unsigned long long s_myAccount = 0;

static bool SB_FileExists( const char *path )
{
	int len = 0;
	byte *buf = (byte *)gEngfuncs.COM_LoadFile((char *)path, 5, &len );

	if( !buf )
		return false;

	gEngfuncs.COM_FreeFile( buf );
	return true;
}

// one texture per path, shared by every row that wants the same picture
static int SB_AvatarTex( const char *path, float flTime )
{
	int i, freeSlot = -1;

	if( !path[0] )
		return 0;

	for( i = 0; i < (int)( sizeof( s_avas ) / sizeof( s_avas[0] )); i++ )
	{
		if( s_avas[i].path[0] && !stricmp( s_avas[i].path, path ))
			break;
		if( freeSlot < 0 && !s_avas[i].path[0] )
			freeSlot = i;
	}

	if( i == ( int )( sizeof( s_avas ) / sizeof( s_avas[0] )))
	{
		// free slot for a path we have never seen: remember it right
		// away, so a MISS is keyed by its path the same way a hit is
		// (the old code only stored the path on success, which is why
		// one failed load stuck for the whole session)
		if( freeSlot < 0 )
			return 0; // every pool entry is in use

		i = freeSlot;
		s_avas[i].tex = 0;
		s_avas[i].next = 0;
		strncpy( s_avas[i].path, path, sizeof( s_avas[i].path ) - 1 );
		s_avas[i].path[sizeof( s_avas[i].path ) - 1] = 0;
	}

	if( s_avas[i].tex > 0 )
		return s_avas[i].tex;

	// -1 = tried and nothing is on disk: re-probe after the delay, the
	// picture may still be on its way over the network
	if( flTime < s_avas[i].next )
		return 0;

	s_avas[i].next = flTime + AVATAR_PROBE_TIME;

	if( !SB_FileExists( path ))
	{
		s_avas[i].tex = -1;
		return 0;
	}

	s_avas[i].tex = gRenderAPI.GL_LoadTexture((char *)path, NULL, 0,
		TF_NEAREST | TF_NOMIPMAP | TF_CLAMP );

	if( s_avas[i].tex <= 0 )
	{
		s_avas[i].tex = -1;
		return 0;
	}

	return s_avas[i].tex;
}

// "STEAM_0:1:905" / "0:0:905" / "[U:1:905]" / steamid64 -> the account
// number reunion/dproto and the goldsource steam emulators put in *sid
static bool SB_SidAccount( const char *sid, unsigned long long *account )
{
	if( !sid || !sid[0] )
		return false;

	if( !strnicmp( sid, "STEAM_", 6 ))
	{
		sid += 6;

		const char *last = strrchr( sid, ':' );
		if( !last || last == sid )
			return false;

		int z = atoi( last + 1 );
		if( z < 0 )
			return false;

		const char *num = last;
		while( num > sid && isdigit((unsigned char)num[-1] ))
			num--;

		long y = atol( num );   // the auth bit is the last digit of the pair
		if( y < 0 )
			return false;

		*account = (unsigned long long)z * 2 + ( y & 1 );
		return *account != 0;
	}
	else if( sid[0] == '[' )
	{
		int y = 0;

		if( sscanf( sid, "[U:1:%d]", &y ) != 1 || y < 0 )
			return false;

		*account = (unsigned long long)y;
		return *account != 0;
	}
	else if( isdigit((unsigned char)sid[0] ))
	{
		unsigned long long v = strtoull( sid, NULL, 10 );

		if( v > 0xFFFFFFFFULL )
		{
			if( v <= 76561197960265728ULL )
				return false;
			v -= 76561197960265728ULL;
		}

		if( v == 0 || v > 0xFFFFFFFFULL )
			return false;

		*account = v;
		return true;
	}

	return false;
}

// <gamedir>/friends/avatars.txt, written by the Steam friends screen:
// "player name<TAB>path" per line. Used for rows I recognise by name.
struct sb_ava_map_t
{
	char name[32];      // same length as CHudScoreboard::MAX_FRIEND_NAME
	char path[MAX_AVA_PATH];
};

static sb_ava_map_t s_avaMap[MAX_SB_AVAMAP];
static int s_iNumAvaMap = 0;

static const char *SB_AvatarForName( const char *name )
{
	int i;

	if( !name || !name[0] )
		return NULL;

	for( i = 0; i < s_iNumAvaMap; i++ )
	{
		if( !stricmp( name, s_avaMap[i].name ))
			return s_avaMap[i].path;
	}

	return NULL;
}

// the Steam friends screen saves my photo here when I sign in; used as
// my logo when the server tells me no *sid of my own
static void SB_LoadMyAccount( void )
{
	s_myAccount = 0;

	int len = 0;
	char *buf = (char *)gEngfuncs.COM_LoadFile((char *)"friends/steamid.txt", 5, &len );

	if( buf )
	{
		unsigned long long v = strtoull( buf, NULL, 10 );

		if( v > 76561197960265728ULL && v <= 76561197960265728ULL + 0xFFFFFFFFULL )
			s_myAccount = v - 76561197960265728ULL;

		gEngfuncs.COM_FreeFile( buf );
	}
}

static void SB_LoadAvatarMap( void )
{
	char line[256];
	char *p, *buf;
	int len = 0;
	int row = 0;

	s_iNumAvaMap = 0;

	buf = (char *)gEngfuncs.COM_LoadFile((char *)"friends/avatars.txt", 5, &len );
	if( !buf )
		return;

	p = buf;

	while( *p && row < MAX_SB_AVAMAP )
	{
		char *eol = strchr( p, '\n' );
		int n = eol ? (int)( eol - p ) : (int)strlen( p );

		if( (int)sizeof( line ) - 1 < n )
			n = sizeof( line ) - 1;

		memcpy( line, p, n );
		line[n] = 0;

		// "name<TAB>path" - the tab is the only separator, Steam names
		// may contain anything else
		char *tab = strchr( line, '\t' );
		if( tab )
		{
			*tab = 0;

			char *end = line + strlen( line );
			while( end > line && ( end[-1] == '\r' || end[-1] == ' ' || end[-1] == '\t' ))
				*--end = 0;

			char *path = tab + 1;
			while( *path == ' ' || *path == '\t' )
				path++;

			char *pend = path + strlen( path );
			while( pend > path && ( pend[-1] == '\r' || pend[-1] == ' ' || pend[-1] == '\t' ))
				*--pend = 0;

			if( line[0] && *path )
			{
				strncpy( s_avaMap[row].name, line, sizeof( s_avaMap[row].name ) - 1 );
				s_avaMap[row].name[sizeof( s_avaMap[row].name ) - 1] = 0;
				strncpy( s_avaMap[row].path, path, sizeof( s_avaMap[row].path ) - 1 );
				s_avaMap[row].path[sizeof( s_avaMap[row].path ) - 1] = 0;
				row++;
			}
		}

		if( !eol )
			break;
		p = eol + 1;
	}

	s_iNumAvaMap = row;
	gEngfuncs.COM_FreeFile( buf );
}

// first path in the list that is actually on disk
static const char *SB_FirstExisting( const char *const *list, int count )
{
	for( int i = 0; i < count; i++ )
	{
		if( list[i] && list[i][0] && SB_FileExists( list[i] ))
			return list[i];
	}

	return NULL;
}

// textures don't survive a vid restart and the files may have changed
static void SB_AvatarsReset( void );

// the picture for one scoreboard slot, resolved as rarely as possible:
// a settled row stays put, an unsettled one is re-evaluated so a file
// that lands while I am in the server still shows up
static const char *SB_SlotPath( int slot, float flTime )
{
	// A7 build 111: a freshly picked/imported logo reached disk - the
	// engine bumped @cl_avatargen (and freed the ref texture), so drop
	// every cached logo texture + path once per bump: THIS scoreboard and
	// the menu (CL_PlayerLogoPath resolves through this very function)
	// then reload the new picture live, no rejoin needed.
	static int s_avatargenSeen = -1;
	cvar_t *avatargen = gEngfuncs.pfnGetCvarPointer( "@cl_avatargen" );
	int gen = avatargen ? (int)avatargen->value : 0;

	if( gen != s_avatargenSeen )
	{
		s_avatargenSeen = gen;
		SB_AvatarsReset();
	}

	char sid[128] = "";
	char pngPath[MAX_AVA_PATH];
	char tgaPath[MAX_AVA_PATH];
	unsigned long long account = 0;
	const char *cands[4];
	int ncands = 0;
	const char *picked = NULL;

	if( slot < 0 || slot > MAX_PLAYERS )
		return NULL;

	hud_player_info_t *pl_info = &g_PlayerInfoList[slot];
	bool mine = pl_info->thisplayer || ( slot == s_localSlot );

	if( s_slotPath[slot].path[0] )
	{
		// one row resolves once and stays: only my own row re-reads, so
		// an image imported from Options shows without reconnecting
		if( !mine || flTime < s_slotPath[slot].next )
			return s_slotPath[slot].path;

		s_slotPath[slot].path[0] = 0;
	}
	else if( s_slotPath[slot].next && flTime < s_slotPath[slot].next )
	{
		// checked a moment ago and there was nothing: don't stat twice a frame
		return NULL;
	}

	s_slotPath[slot].next = 0;

	strncpy( sid, gEngfuncs.PlayerInfo_ValueForKey( slot, "*sid" ), sizeof( sid ) - 1 );
	sid[sizeof( sid ) - 1] = 0;

	pngPath[0] = tgaPath[0] = 0;

	if( SB_SidAccount( sid, &account ))
	{
		snprintf( pngPath, sizeof( pngPath ), "media/avatars/av_%llu.png", account );
		snprintf( tgaPath, sizeof( tgaPath ), "media/avatars/av_%llu.tga", account );
	}

	if( mine )
	{
		// A7 build 139 (item 4): the Steam photo beats the imported
		// picture - a signed-in account owns the avatar (the Options
		// import path is refused while friends/steamid.txt exists)
		cands[ncands++] = "media/avatars/avatar_self.png";     // my Steam photo
		cands[ncands++] = "media/avatars/avatar_custom.png";   // "Import image..."

		if( pngPath[0] )
			cands[ncands++] = pngPath;
		else if( s_myAccount )
		{
			static char minePath[MAX_AVA_PATH];
			snprintf( minePath, sizeof( minePath ), "media/avatars/av_%llu.png", s_myAccount );
			cands[ncands++] = minePath;
		}

		picked = SB_FirstExisting( cands, ncands );

		// last resort: the menu's logos/ pick (kept as a plain path)
		if( !picked && s_cl_playerlogo && s_cl_playerlogo->string[0] )
		{
			const char *legacy[1];
			legacy[0] = s_cl_playerlogo->string;
			picked = SB_FirstExisting( legacy, 1 );
		}
	}
	else
	{
		const char *byName = SB_AvatarForName( pl_info->name );

		if( byName && byName[0] )
		{
			cands[0] = byName;
			picked = SB_FirstExisting( cands, 1 );
		}

		if( !picked )
		{
			ncands = 0;
			cands[ncands++] = pngPath;
			cands[ncands++] = tgaPath;
			picked = SB_FirstExisting( cands, ncands );
		}
	}

	s_slotPath[slot].path[0] = 0;

	if( picked )
	{
		strncpy( s_slotPath[slot].path, picked, sizeof( s_slotPath[slot].path ) - 1 );
		s_slotPath[slot].path[sizeof( s_slotPath[slot].path ) - 1] = 0;
	}

	if( picked && !mine )
	{
		// a settled other row: no more file checks until a vid restart
		s_slotPath[slot].next = 0;
	}
	else
	{
		// nothing YET (any row) or "my picture may have been replaced"
		// (my row): re-check a couple of times a second instead of
		// giving up
		s_slotPath[slot].next = flTime + AVATAR_PROBE_TIME;
	}

	return s_slotPath[slot].path[0] ? s_slotPath[slot].path : NULL;
}

// A7 build 110: exported for the MENU scoreboard (cl_dll/cdll_int.cpp ->
// IGameClientExports::GetPlayerLogoPath) - the logo/avatar FILE for one
// engine slot, gamedir-relative, or NULL. One wrapper over SB_SlotPath so
// the menu and this legacy HUD scoreboard share the exact same resolution
// chain (friends/avatars.txt, media/avatars/av_<account>, avatar_custom/
// avatar_self, cl_playerlogo).
const char *CL_PlayerLogoPath( int slot )
{
	if( slot < 1 || slot >= MAX_PLAYERS )
		return NULL;

	return SB_SlotPath( slot, gHUD.m_flTime );
}

// the texture for one slot, or 0 when that slot has no picture
static int SB_SlotAvatar( int slot, float flTime )
{
	const char *path = SB_SlotPath( slot, flTime );

	return path ? SB_AvatarTex( path, flTime ) : 0;
}

// draws the picture plate at (x, ypos) and returns where a name may start
static int SB_DrawAvatar( int tex, int x, int ypos )
{
	const int sz = LOGO_SIZE;
	const float scale = gHUD.m_flScale;

	// build 101: the plate the PC scoreboard gives every player picture -
	// dark socket first, the image one pixel in, a thin frame around it,
	// so a logo reads as a card instead of a stamp lost on the row
	FillRGBA( x, ypos, sz, sz, 6, 10, 14, 240 );
	FillRGBA( x, ypos, sz, 1, 255, 140, 0, 120 );
	FillRGBA( x, ypos + sz - 1, sz, 1, 255, 140, 0, 120 );
	FillRGBA( x, ypos, 1, sz, 255, 140, 0, 120 );
	FillRGBA( x + sz - 1, ypos, 1, sz, 255, 140, 0, 120 );

	gRenderAPI.GL_SelectTexture( 0 );
	gRenderAPI.GL_Bind( 0, tex );
	gEngfuncs.pTriAPI->RenderMode( kRenderTransAlpha );
	gEngfuncs.pTriAPI->Color4f( 1.0f, 1.0f, 1.0f, 1.0f );

	DrawUtils::Draw2DQuad(( x + 1 ) * scale, ( ypos + 1 ) * scale,
		( x + sz - 1 ) * scale, ( ypos + sz - 1 ) * scale );

	gEngfuncs.pTriAPI->RenderMode( kRenderNormal );
	return x + sz + 4;
}

// textures don't survive a vid restart and the files may have changed
static void SB_AvatarsReset( void )
{
	for( int i = 0; i < (int)( sizeof( s_avas ) / sizeof( s_avas[0] )); i++ )
	{
		if( s_avas[i].tex > 0 )
			gRenderAPI.GL_FreeTexture( s_avas[i].tex );
		s_avas[i].tex = 0;
		s_avas[i].path[0] = 0;
		s_avas[i].next = 0;
	}

	memset( s_slotPath, 0, sizeof( s_slotPath ));

	SB_LoadMyAccount();
	SB_LoadAvatarMap();
}

// A7 user request: the Steam friends the WebView screen saved
// (<gamedir>/friends/names.txt, one UTF-8 name per line)
void CHudScoreboard::LoadFriendList()
{
	int len = 0;
	m_iNumFriends = 0;

	char *pFileBuf = (char *)gEngfuncs.COM_LoadFile( (char *)"friends/names.txt", 5, &len );
	if( !pFileBuf )
		return;

	const char *p = pFileBuf;

	while( *p && m_iNumFriends < MAX_SB_FRIENDS )
	{
		const char *eol = strchr( p, '\n' );
		int n = eol ? (int)( eol - p ) : (int)strlen( p );

		while( n > 0 && ( p[n - 1] == '\r' || p[n - 1] == ' ' || p[n - 1] == '\t' ))
			n--;

		if( n > 0 )
		{
			if( n >= MAX_FRIEND_NAME )
				n = MAX_FRIEND_NAME - 1;

			memcpy( m_szFriends[m_iNumFriends], p, n );
			m_szFriends[m_iNumFriends][n] = 0;
			m_iNumFriends++;
		}

		if( !eol )
			break;
		p = eol + 1;
	}

	gEngfuncs.COM_FreeFile( pFileBuf );
}

bool CHudScoreboard::IsFriend( const char *name ) const
{
	if( !name || !*name )
		return false;

	for( int i = 0; i < m_iNumFriends; i++ )
	{
		if( !stricmp( name, m_szFriends[i] ))
			return true;
	}

	return false;
}


int CHudScoreboard :: VidInit( void )
{
	xstart = ScreenWidth * 0.125f;
	xend = ScreenWidth - xstart;
	ystart = 100;
	yend = ScreenHeight - ystart;
	m_bForceDraw = false;

	// renderer textures don't survive a vid restart, and the friends
	// screen may have written new lists while we were loading - drop the
	// whole picture cache and read both lists again
	SB_AvatarsReset();

	// the friends screen may have written a new list while we were loading
	LoadFriendList();

	// Load sprites here
	return 1;
}

void CHudScoreboard :: InitHUDData( void )
{
	memset( g_PlayerExtraInfo, 0, sizeof g_PlayerExtraInfo );
	m_iLastKilledBy = 0;
	m_fLastKillTime = 0;
	m_iPlayerNum = 0;
	m_iNumTeams = 0;
	memset( g_TeamInfo, 0, sizeof g_TeamInfo );

	for ( int i = 1; i <= MAX_PLAYERS; i++ )
	{
		// a1ba: get the cl.playernum from the engine
		// it shouldn't ever change during normal gameplay
		if( !m_iPlayerNum && EV_IsLocal( i ))
			m_iPlayerNum = i;

		g_PlayerExtraInfo[i].sb_health = -1;
		g_PlayerExtraInfo[i].sb_account = -1;
	}

	// the logo chain needs to know my own slot without reaching into the
	// class from the file-static helpers
	s_localSlot = m_iPlayerNum;
	SB_AvatarsReset();

	m_iFlags &= ~HUD_DRAW;  // starts out inactive

	m_iFlags |= HUD_INTERMISSION; // is always drawn during an intermission
}

bool CHudScoreboard :: ShouldDrawScoreboard() const
{
	if( m_bForceDraw )
		return true;

	if( m_bShowscoresHeld || gHUD.m_iIntermission )
		return true;

	if( cl_show_scoreboard_on_death && cl_show_scoreboard_on_death->value && gHUD.m_Health.m_iHealth <= 0 )
		return true;

	return false;
}

int CHudScoreboard :: Draw( float flTime )
{
	if( !ShouldDrawScoreboard( ))
		return 1;

	// A7 build 109: route the TAB scoreboard to the menu-side scoreboard
	// (rootcreep "menus: Scoreboard implementation" port). DrawScoreboard()
	// -> udll_int.cpp -> UI_DrawScoreboard() which lazily Init()+VidInit()s
	// the CMenuScoreboard on first use and draws it raw for this frame -
	// no menu activation, no input capture: release TAB and it simply does
	// not draw next frame. The legacy HUD panel below stays as the fallback
	// when the menu interface is unavailable (old touch menus).
	if( g_pMenu )
	{
		g_pMenu->DrawScoreboard();
		return 1;
	}

	if( !m_bForceDraw )
	{
		xstart     = 0.125f * ScreenWidth;
		xend       = ScreenWidth - xstart;
		ystart     = 90;
		yend       = ScreenHeight - ystart;

		// A7 round 100 - retail CS 1.6 colour set: the panel is the
		// GoldSource blue-black plate (never flat black), a touch more
		// opaque than the stock 153 so the rows read over a bright map,
		// and the drawn frame stays the classic orange.
		m_colors.r = 10;
		m_colors.g = 14;
		m_colors.b = 18;
		m_colors.a = 170;
		m_bDrawStroke = true;
	}

	return DrawScoreboard(flTime);
}

int CHudScoreboard :: DrawScoreboard( float fTime )
{
	GetAllPlayersInfo();
	char ServerName[90];

//	Packetloss removed on Kelly 'shipping nazi' Bailey's orders
//	if ( cl_showpacketloss && cl_showpacketloss->value && ( ScreenWidth >= 400 ) )
//	{
//		can_show_packetloss = 1;
//	}

	// just sort the list on the fly
	// list is sorted first by frags, then by deaths
	float list_slot = 0;

	// calculate columns sizes
	g_Columns[COL_PING] = Column( xend - 15, Localize( "#PlayerPing" ) );
	g_Columns[COL_PING].end = min( g_Columns[COL_PING].end, g_Columns[COL_PING].start - DrawUtils::HudStringLen( "9999" ) );

	g_Columns[COL_DEATHS] = Column( g_Columns[COL_PING].end - 10, Localize( "#PlayerDeath" ) );
	g_Columns[COL_DEATHS].end = min( g_Columns[COL_DEATHS].end, g_Columns[COL_DEATHS].start - DrawUtils::HudStringLen( "9999" ) );

	g_Columns[COL_KILLS] = Column( g_Columns[COL_DEATHS].end - 10, Localize( "#PlayerScore" ) );
	g_Columns[COL_KILLS].end = min( g_Columns[COL_KILLS].end, g_Columns[COL_KILLS].start - DrawUtils::HudStringLen( "9999" ) );

	g_Columns[COL_MONEY] = Column( g_Columns[COL_KILLS].end - 10, Localize( "#Cstrike_ACCOUNT" ) );
	g_Columns[COL_MONEY].end = min( g_Columns[COL_MONEY].end, g_Columns[COL_MONEY].start - DrawUtils::HudStringLen( "$999999" ) );

	g_Columns[COL_HP] = Column( g_Columns[COL_MONEY].end - 10, Localize( "#Cstrike_HEALTH" ) );
	g_Columns[COL_HP].end = min( g_Columns[COL_HP].end, g_Columns[COL_HP].start - DrawUtils::HudStringLen( "999999" ) );

	g_Columns[COL_ATTRIB] = Column( g_Columns[COL_HP].end - 10 );
	g_Columns[COL_ATTRIB].end = g_Columns[COL_ATTRIB].start - DrawUtils::HudStringLen( "#Cstrike_DEFUSE_KIT" );

	// A7 round 100 / build 101: names start after the logo gutter - one
	// LOGO_GUTTER wide (2 + 32 + 6 = 40 in build 103; 2 + 24 + 6 in 102,
	// 2 + 17 + 6 in 101, the old 19/13 pair before), so the plate reads
	// as a picture and the name lines up with the header text
	g_Columns[COL_NAME] = Column( xstart + LOGO_GUTTER, nullptr, false );
	g_Columns[COL_NAME].end = g_Columns[COL_ATTRIB].end - 10;

	// print the heading line

	DrawUtils::DrawRectangle(xstart, ystart, xend - xstart, yend - ystart,
		m_colors.r, m_colors.g, m_colors.b, m_colors.a, m_bDrawStroke);

	// A7 round 100: the retail title row sits on a slightly lighter plate
	// inside the frame - it separates the header from the list the way the
	// original does without adding a line of its own
	FillRGBABlend( xstart + 2, ystart + 2, xend - xstart - 4, ROW_GAP + 7,
		255, 255, 255, 12 );

	int ypos = ystart + (list_slot * ROW_GAP) + 5;

	if( gHUD.m_szServerName[0] )
		// snprintf( ServerName, 80, "%s", (char*)(gHUD.m_Teamplay ? "TEAMS" : "PLAYERS"), gHUD.m_szServerName );
		strncpy( ServerName, gHUD.m_szServerName, 80 );
	else
		strncpy( ServerName, gHUD.m_Teamplay ? "TEAMS" : "PLAYERS", 80 );

	DrawUtils::DrawHudString( g_Columns[COL_NAME].start, ypos, g_Columns[COL_NAME].end, ServerName, 255, 140, 0 );
	DrawUtils::DrawHudStringReverse( g_Columns[COL_HP].start, ypos, g_Columns[COL_HP].end, g_Columns[COL_HP].name, 255, 140, 0 );
	DrawUtils::DrawHudStringReverse( g_Columns[COL_MONEY].start, ypos, g_Columns[COL_MONEY].end, g_Columns[COL_MONEY].name, 255, 140, 0 );
	DrawUtils::DrawHudStringReverse( g_Columns[COL_KILLS].start, ypos, g_Columns[COL_KILLS].end, g_Columns[COL_KILLS].name, 255, 140, 0 );
	DrawUtils::DrawHudStringReverse( g_Columns[COL_DEATHS].start, ypos, g_Columns[COL_DEATHS].end, g_Columns[COL_DEATHS].name, 255, 140, 0 );
	DrawUtils::DrawHudStringReverse( g_Columns[COL_PING].start, ypos, g_Columns[COL_PING].end, g_Columns[COL_PING].name, 255, 140, 0 );

	list_slot += 2;
	ypos = ystart + (list_slot * ROW_GAP);
	FillRGBA( xstart, ypos, xend - xstart, 1, 255, 140, 0, 255);  // draw the separator line

	list_slot += 0.8;

	if ( gHUD.m_Teamplay )
	{
		DrawTeams( list_slot );
	}
	else
	{
		// it's not teamplay,  so just draw a simple player list
		DrawPlayers( list_slot );
	}
	return 1;
}

int CHudScoreboard :: DrawTeams( float list_slot )
{
	int j;
	int ypos = ystart + (list_slot * ROW_GAP) + 5;

	// clear out team scores
	for ( int i = 1; i <= m_iNumTeams; i++ )
	{
		if ( !g_TeamInfo[i].scores_overriden )
			g_TeamInfo[i].frags = g_TeamInfo[i].deaths = 0;
		g_TeamInfo[i].sumping = 0;
		g_TeamInfo[i].players = 0;
		g_TeamInfo[i].already_drawn = FALSE;
	}

	// recalc the team scores, then draw them
	for ( int i = 1; i < MAX_PLAYERS; i++ )
	{
		if ( !g_PlayerInfoList[i].name || !g_PlayerInfoList[i].name[0] )
			continue; // empty player slot, skip

		if ( g_PlayerExtraInfo[i].teamname[0] == 0 )
			continue; // skip over players who are not in a team

		// find what team this player is in
		for ( j = 1; j <= m_iNumTeams; j++ )
		{
			if ( !stricmp( g_PlayerExtraInfo[i].teamname, g_TeamInfo[j].name ) )
				break;
		}

		if ( j > m_iNumTeams )  // player is not in a team, skip to the next guy
			continue;

		if ( !g_TeamInfo[j].scores_overriden )
		{
			g_TeamInfo[j].frags += g_PlayerExtraInfo[i].frags;
			g_TeamInfo[j].deaths += g_PlayerExtraInfo[i].deaths;
		}

		g_TeamInfo[j].sumping += g_PlayerInfoList[i].ping;

		if ( g_PlayerInfoList[i].thisplayer )
			g_TeamInfo[j].ownteam = TRUE;
		else
			g_TeamInfo[j].ownteam = FALSE;

		g_TeamInfo[j].players++;
	}

	// Draw the teams
	int iSpectatorPos = -1;

	while( true )
	{
		int highest_frags = -99999; int lowest_deaths = 99999;
		int best_team = 0;

		for ( int i = 1; i <= m_iNumTeams; i++ )
		{
			// don't draw team without players
			if ( g_TeamInfo[i].players <= 0 )
				continue;

			if (!strnicmp(g_TeamInfo[i].name, "SPECTATOR", MAX_TEAM_NAME))
			{
				iSpectatorPos = i;
				continue;
			}

			if ( !g_TeamInfo[i].already_drawn && g_TeamInfo[i].frags >= highest_frags )
			{
				if ( g_TeamInfo[i].frags > highest_frags || g_TeamInfo[i].deaths < lowest_deaths )
				{
					best_team = i;
					lowest_deaths = g_TeamInfo[i].deaths;
					highest_frags = g_TeamInfo[i].frags;
				}
			}
		}

		// draw the best team on the scoreboard
		if ( !best_team )
		{
			// if spectators is found and still not drawn
			if( iSpectatorPos != -1 && g_TeamInfo[iSpectatorPos].already_drawn == FALSE )
				best_team = iSpectatorPos;
			else break;
		}
		// draw out the best team
		team_info_t *team_info = &g_TeamInfo[best_team];

		// don't draw team without players
		if ( team_info->players <= 0 )
			continue;

		ypos = ystart + (list_slot * ROW_GAP);

		// check we haven't drawn too far down
		if ( ypos > yend )  // don't draw to close to the lower border
			break;

		int r, g, b;
		char teamName[64];

		char numPlayers[16];
		sprintf( numPlayers, "%d", team_info->players );

		char fmtString[32];
		const char *fmtStringName = team_info->players == 1 ? "#Cstrike_ScoreBoard_Player" : "#Cstrike_ScoreBoard_Players";
		strncpy( fmtString, Localize( fmtStringName ), sizeof( fmtString ) );
		fmtString[sizeof( fmtString ) - 1] = 0;

		if ( !strcmp( fmtString, fmtStringName ) )
		{
			const char *fallback = team_info->players == 1 ? "%s1    -   %s2 player" : "%s1    -   %s2 players";
			strncpy( fmtString, fallback, sizeof( fmtString ) );
			fmtString[sizeof( fmtString ) - 1] = 0;
		}

		GetTeamColor( r, g, b, team_info->teamnumber );
		switch ( team_info->teamnumber )
		{
		case TEAM_TERRORIST:
		{
			const char *args[2] = { Localize( "#Cstrike_ScoreBoard_Ter" ), numPlayers };
			Localize_Format( teamName, sizeof( teamName ), fmtString, args, 2 );
			DrawUtils::DrawHudNumberString( g_Columns[COL_KILLS].start, ypos, g_Columns[COL_KILLS].end, team_info->frags, r, g, b );
			break;
		}
		case TEAM_CT:
		{
			const char *args[2] = { Localize( "#Cstrike_ScoreBoard_CT" ), numPlayers };
			Localize_Format( teamName, sizeof( teamName ), fmtString, args, 2 );
			DrawUtils::DrawHudNumberString( g_Columns[COL_KILLS].start, ypos, g_Columns[COL_KILLS].end, team_info->frags, r, g, b );
			break;
		}
		case TEAM_SPECTATOR:
		case TEAM_UNASSIGNED:
			strncpy( teamName, Localize( "#Spectators" ), sizeof( teamName ) );
			break;
		}

		DrawUtils::DrawHudString( g_Columns[COL_NAME].start, ypos, g_Columns[COL_NAME].end, teamName, r, g, b );
		DrawUtils::DrawHudNumberString( g_Columns[COL_PING].start, ypos, g_Columns[COL_PING].end, team_info->sumping / team_info->players, r, g, b );

		team_info->already_drawn = TRUE;  // set the already_drawn to be TRUE, so this team won't get drawn again

		// draw underline
		list_slot += 1.2f;
		FillRGBA( xstart, ystart + (list_slot * ROW_GAP), xend - xstart, 1, r, g, b, 255);

		list_slot += 0.4f;
		// draw all the players that belong to this team, indented slightly
		list_slot = DrawPlayers( list_slot, 10, team_info->name );
	}

	// draw all the players who are not in a team
	list_slot += 4.0f;
	DrawPlayers( list_slot, 0, "" );

	return 1;
}

// returns the ypos where it finishes drawing
int CHudScoreboard :: DrawPlayers( float list_slot, int nameoffset, const char *team )
{
	// draw the players, in order,  and restricted to team if set
	while ( 1 )
	{
		// Find the top ranking player
		int highest_frags = -99999;	int lowest_deaths = 99999;
		int best_player = 0;

		for ( int i = 1; i < MAX_PLAYERS; i++ )
		{
			if ( g_PlayerInfoList[i].name && g_PlayerExtraInfo[i].frags >= highest_frags )
			{
				if ( !(team && stricmp(g_PlayerExtraInfo[i].teamname, team)) )  // make sure it is the specified team
				{
					extra_player_info_t *pl_info = &g_PlayerExtraInfo[i];
					if ( pl_info->frags > highest_frags || pl_info->deaths < lowest_deaths )
					{
						best_player = i;
						lowest_deaths = pl_info->deaths;
						highest_frags = pl_info->frags;
					}
				}
			}
		}

		if ( !best_player )
			break;

		// draw out the best player
		hud_player_info_t *pl_info = &g_PlayerInfoList[best_player];

		int ypos = ystart + (list_slot * ROW_GAP);

		// check we haven't drawn too far down
		if ( ypos > yend )  // don't draw to close to the lower border
			break;

		int r = 255, g = 255, b = 255;
		float *colors = GetClientColor( best_player );
		r *= colors[0];
		g *= colors[1];
		b *= colors[2];

		if(pl_info->thisplayer) // hey, it's me!
		{
			// A7 round 100: a firmer own-row band, so my line still reads
			// at a glance with a logo plate on it
			FillRGBABlend( xstart, ypos, xend - xstart, ROW_GAP, 255, 255, 255, 26 );
		}

		DrawUtils::DrawHudString( g_Columns[COL_NAME].start + nameoffset, ypos, g_Columns[COL_NAME].start + 350, pl_info->name, r, g, b );

		// A7 round 100: the logo column. My own row gets the imported
		// image / my Steam photo, any other row gets a Steam friend's
		// photo (matched by name) or that player's *sid photo. It draws
		// for whichever row has a picture, on xash AND goldsource servers
		// alike - nothing server-side feeds it, and a picture that is not
		// on disk YET is retried instead of written off (that miss was
		// why some servers showed no logo at all).
		const int logoTex = SB_SlotAvatar( best_player, gHUD.m_flTime );

		if( logoTex > 0 )
		{
			// build 103: the 32 px plate sits centred in its 36 px row
			// so it reads like the PC board's player pictures
			SB_DrawAvatar( logoTex, g_Columns[COL_NAME].start + nameoffset - LOGO_GUTTER,
				ypos + ( ROW_GAP - LOGO_SIZE ) / 2 );
		}
		else if( IsFriend( pl_info->name ))
		{
			const int bx = g_Columns[COL_NAME].start + nameoffset
				+ DrawUtils::HudStringLen( pl_info->name ) + 6;

			if( bx + 11 <= g_Columns[COL_NAME].start + 350 )
			{
				FillRGBA( bx, ypos + 2, 11, 11, 102, 192, 244, 255 );
				FillRGBA( bx + 2, ypos + 4, 7, 7, 8, 20, 34, 255 );
			}
		}

		if( cl_showplayerversion->value == 0.0f )
		{
			if( team && stricmp( team, "SPECTATOR" ))
			{
				// draw bomb( if player have the bomb )
				if( g_PlayerExtraInfo[best_player].dead )
					DrawUtils::DrawHudStringReverse( g_Columns[COL_ATTRIB].start, ypos, g_Columns[COL_ATTRIB].end, Localize( "#Cstrike_DEAD" ), r, g, b );
				else if( g_PlayerExtraInfo[best_player].has_c4 )
					DrawUtils::DrawHudStringReverse( g_Columns[COL_ATTRIB].start, ypos, g_Columns[COL_ATTRIB].end, Localize( "#Cstrike_BOMB" ), r, g, b );
				else if( g_PlayerExtraInfo[best_player].vip )
					DrawUtils::DrawHudStringReverse( g_Columns[COL_ATTRIB].start, ypos, g_Columns[COL_ATTRIB].end, Localize( "#Cstrike_VIP" ),  r, g, b );
				else if (g_PlayerExtraInfo[best_player].has_defuse_kit )
					DrawUtils::DrawHudStringReverse( g_Columns[COL_ATTRIB].start, ypos, g_Columns[COL_ATTRIB].end, Localize( "#Cstrike_DEFUSE_KIT" ),  r, g, b );
			}
		}
		else
		{
			DrawUtils::DrawHudStringReverse( g_Columns[COL_ATTRIB].start, ypos, g_Columns[COL_ATTRIB].end, gEngfuncs.PlayerInfo_ValueForKey( best_player, "cscl_ver" ),  r, g, b );
		}

		if ( g_PlayerExtraInfo[best_player].sb_health >= 0 && !g_PlayerExtraInfo[best_player].dead )
		{
			if ( gHUD.m_pShowHealth->value )
			{
				static char buf[64];
				sprintf( buf, "%d", g_PlayerExtraInfo[best_player].sb_health );
				DrawUtils::DrawHudStringReverse( g_Columns[COL_HP].start, ypos, g_Columns[COL_HP].end, buf, r, g, b );
			}
		}

		if ( g_PlayerExtraInfo[best_player].sb_account >= 0 )
		{
			if ( gHUD.m_pShowMoney->value )
			{
				static char buf[64];
				sprintf( buf, "$%d", g_PlayerExtraInfo[best_player].sb_account );
				DrawUtils::DrawHudStringReverse( g_Columns[COL_MONEY].start, ypos, g_Columns[COL_MONEY].end, buf, r, g, b );
			}
		}

		// draw kills (right to left)
		if( team && stricmp( team, "SPECTATOR" ) )
		{
			DrawUtils::DrawHudNumberString( g_Columns[COL_KILLS].start, ypos, g_Columns[COL_KILLS].end, g_PlayerExtraInfo[best_player].frags, r, g, b );

			// draw deaths
			DrawUtils::DrawHudNumberString( g_Columns[COL_DEATHS].start, ypos, g_Columns[COL_DEATHS].end, g_PlayerExtraInfo[best_player].deaths, r, g, b );
		}

		// draw ping & packetloss
		const char *value;
		if( pl_info->ping <= 5  // must be 0, until Xash's bug not fixed
			&& ( value = gEngfuncs.PlayerInfo_ValueForKey( best_player, "*bot" ) )
			&& atoi( value ) > 0 )
		{
			DrawUtils::DrawHudStringReverse( g_Columns[COL_PING].start, ypos, g_Columns[COL_PING].end, "BOT", r, g, b );
		}
		else
		{
			static char buf[64];
			sprintf( buf, "%d", pl_info->ping );
			DrawUtils::DrawHudStringReverse( g_Columns[COL_PING].start, ypos, g_Columns[COL_PING].end, buf, r, g, b );
		}

		pl_info->name = NULL;  // set the name to be NULL, so this client won't get drawn again
		list_slot++;
	}

	list_slot += 2.0f;

	return list_slot;
}


void CHudScoreboard :: GetAllPlayersInfo( void )
{
	memset( &g_PlayerInfoList[0], 0, sizeof( g_PlayerInfoList[0] ));

	for( int i = 1; i < MAX_PLAYERS; i++ )
		GetPlayerInfo( i, &g_PlayerInfoList[i] );
}

int CHudScoreboard :: MsgFunc_ScoreInfo( const char *pszName, int iSize, void *pbuf )
{
	m_iFlags |= HUD_DRAW;

	BufferReader reader( pszName, pbuf, iSize );
	short cl = reader.ReadByte();
	short frags = reader.ReadShort();
	short deaths = reader.ReadShort();
	short playerclass = reader.ReadShort();
	reader.ReadShort();

	if ( cl > 0 && cl <= MAX_PLAYERS )
	{
		g_PlayerExtraInfo[cl].frags = frags;
		g_PlayerExtraInfo[cl].deaths = deaths;
		g_PlayerExtraInfo[cl].playerclass = playerclass;

		//gViewPort->UpdateOnPlayerInfo();
	}

	return 1;
}

// Message handler for TeamInfo message
// accepts two values:
//		byte: client number
//		string: client team name
int CHudScoreboard :: MsgFunc_TeamInfo( const char *pszName, int iSize, void *pbuf )
{
	BufferReader reader( pszName, pbuf, iSize );
	short cl = reader.ReadByte();
	int teamNumber = 0;

	if ( cl > 0 && cl <= MAX_PLAYERS )
	{
		// set the players team
		char teamName[MAX_TEAM_NAME];
		strncpy( teamName, reader.ReadString(), MAX_TEAM_NAME );
		teamName[MAX_TEAM_NAME-1] = 0;

		if( !strcmp( teamName, "TERRORIST") )
			teamNumber = TEAM_TERRORIST;
		else if( !strcmp( teamName, "CT") )
			teamNumber = TEAM_CT;
		else if( !strcmp( teamName, "SPECTATOR" ) )
		{
			teamNumber = TEAM_SPECTATOR;
		}
		else if( !strcmp( teamName, "UNASSIGNED" ) )
		{
			teamNumber = TEAM_UNASSIGNED;
			strncpy( teamName, "SPECTATOR", MAX_TEAM_NAME );
		}
		// just in case
		else teamNumber = TEAM_UNASSIGNED;

		strncpy( g_PlayerExtraInfo[cl].teamname, teamName, MAX_TEAM_NAME );
		g_PlayerExtraInfo[cl].teamnumber = teamNumber;
	}

	// rebuild the list of teams

	// clear out player counts from teams
	for ( int i = 1; i <= m_iNumTeams; i++ )
	{
		g_TeamInfo[i].players = 0;
	}

	// rebuild the team list
	GetAllPlayersInfo();
	m_iNumTeams = 0;

	for ( int i = 1; i < MAX_PLAYERS; i++ )
	{
		int j;
		//if ( g_PlayerInfoList[i].name == NULL )
		//	continue;

		if ( g_PlayerExtraInfo[i].teamname[0] == 0 )
			continue; // skip over players who are not in a team

		// is this player in an existing team?
		for ( j = 1; j <= m_iNumTeams; j++ )
		{
			if ( g_TeamInfo[j].name[0] == '\0' )
				break;

			if ( !stricmp( g_PlayerExtraInfo[i].teamname, g_TeamInfo[j].name ) )
				break;
		}

		if ( j > m_iNumTeams )
		{
			// they aren't in a listed team, so make a new one
			for ( j = 1; j <= m_iNumTeams; j++ )
			{
				if ( g_TeamInfo[j].name[0] == '\0' )
					break;
			}


			m_iNumTeams = max( j, m_iNumTeams );

			strncpy( g_TeamInfo[j].name, g_PlayerExtraInfo[i].teamname, MAX_TEAM_NAME );
			g_TeamInfo[j].teamnumber = g_PlayerExtraInfo[i].teamnumber;
			g_TeamInfo[j].players = 0;
		}

		g_TeamInfo[j].players++;
	}

	// clear out any empty teams
	for ( int i = 1; i <= m_iNumTeams; i++ )
	{
		if ( g_TeamInfo[i].players < 1 )
			memset( &g_TeamInfo[i], 0, sizeof(team_info_t) );
	}

	return 1;
}

// Message handler for TeamScore message
// accepts three values:
//		string: team name
//		short: teams kills
//		short: teams deaths
// if this message is never received, then scores will simply be the combined totals of the players.
int CHudScoreboard :: MsgFunc_TeamScore( const char *pszName, int iSize, void *pbuf )
{
	BufferReader reader( pszName, pbuf, iSize );
	char *TeamName = reader.ReadString();
	int i;

	// find the team matching the name
	for ( i = 0; i < m_iNumTeams; i++ )
	{
		if ( !stricmp( TeamName, g_TeamInfo[i].name ) )
			break;
	}
	if ( i > m_iNumTeams )
	{
		reader.Flush();
		return 1;
	}

	// use this new score data instead of combined player scores
	g_TeamInfo[i].scores_overriden = TRUE;
	g_TeamInfo[i].frags = reader.ReadShort();
	// g_TeamInfo[i].deaths = reader.ReadShort();

	return 1;
}

void CHudScoreboard :: DeathMsg( int killer, int victim )
{
	// if we were the one killed,  or the world killed us, set the scoreboard to indicate suicide
	if ( victim == m_iPlayerNum || killer == 0 )
	{
		m_iLastKilledBy = killer ? killer : m_iPlayerNum;
		m_fLastKillTime = gHUD.m_flTime + 10;	// display who we were killed by for 10 seconds

		if ( killer == m_iPlayerNum )
			m_iLastKilledBy = m_iPlayerNum;
	}
}



void CHudScoreboard :: UserCmd_ShowScores( void )
{
	m_bForceDraw = false;
	m_bShowscoresHeld = true;
}

void CHudScoreboard :: UserCmd_HideScores( void )
{
	m_bForceDraw = m_bShowscoresHeld = false;
}


void CHudScoreboard	:: UserCmd_ShowScoreboard2()
{
	if( gEngfuncs.Cmd_Argc() != 9 )
	{
		ConsolePrint("showscoreboard2 <xstart> <xend> <ystart> <yend> <r> <g> <b> <a>");
	}

	xstart     = atof(gEngfuncs.Cmd_Argv(1)) * ScreenWidth;
	xend       = atof(gEngfuncs.Cmd_Argv(2)) * ScreenWidth;
	ystart     = atof(gEngfuncs.Cmd_Argv(3)) * ScreenHeight;
	yend       = atof(gEngfuncs.Cmd_Argv(4)) * ScreenHeight;
	m_colors.r = atoi(gEngfuncs.Cmd_Argv(5));
	m_colors.b = atoi(gEngfuncs.Cmd_Argv(6));
	m_colors.b = atoi(gEngfuncs.Cmd_Argv(7));
	m_colors.a = atoi(gEngfuncs.Cmd_Argv(8));
	m_bDrawStroke = false;
	m_bForceDraw = true;
}

void CHudScoreboard :: UserCmd_HideScoreboard2()
{
	m_bForceDraw = m_bShowscoresHeld = false; // and disable it
}
