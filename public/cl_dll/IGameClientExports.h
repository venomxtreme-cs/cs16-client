//========= Copyright � 1996-2002, Valve LLC, All rights reserved. ============
//
// Purpose: 
//
// $NoKeywords: $
//=============================================================================

#ifndef IGAMECLIENTEXPORTS_H
#define IGAMECLIENTEXPORTS_H
#ifdef _WIN32
#pragma once
#endif

#include "interface.h"
#include <stdint.h> // A7 build 109: uint64_t (SteamID) in GetPlayerSteamInfo

// A7 build 109: forward declarations for the scoreboard payloads. Done here
// so this header stays self-contained in BOTH compile worlds - cl_dll sees
// engine/cdll_int.h + cl_dll/hud.h, mainui sees
// sdk_includes/common/ClientInfo.h. The two definitions have identical
// layouts and are never parsed into the same TU, so a forward declaration
// is enough for pointer parameters.
struct team_info_t;
struct extra_player_info_t;
struct hud_player_info_s;

//-----------------------------------------------------------------------------
// Purpose: Exports a set of functions for the GameUI interface to interact with the game client
//-----------------------------------------------------------------------------
class IGameClientExports : public IBaseInterface
{
public:
	// returns the name of the server the user is connected to, if any
	virtual const char *GetServerHostName() = 0;

	// ingame voice manipulation
	virtual bool IsPlayerGameVoiceMuted(int playerIndex) = 0;
	virtual void MutePlayerGameVoice(int playerIndex) = 0;
	virtual void UnmutePlayerGameVoice(int playerIndex) = 0;

	virtual const char *GetLevelName( void ) = 0;

	virtual int GetLocalPlayerTeam( void ) = 0;

	// Appended for the in-game "Current players" (player list) window.
	// IMPORTANT: append-only - do not reorder, do not touch
	// GAMECLIENTEXPORTS_INTERFACE_VERSION; both cl_dll (CClientExports) and
	// mainui (g_pClient) must compile against this same class layout.

	// playerIndex is always an ENGINE PLAYER SLOT: 1..GetMaxPlayers()
	// (GoldSrc convention, same as CVoiceStatus::IsPlayerBlocked()/
	// SetPlayerBlockedState() and the [1; MAX_PLAYERS) scoreboard loops).
	// Returns the number of player slots on the server (clamped by the
	// implementation to the client's player array bounds).
	virtual int GetMaxPlayers( void ) = 0;

	// Returns the scoreboard name of the given player slot, including the
	// local player. Returns NULL or "" when the slot is empty. The returned
	// pointer belongs to the engine and is only valid until the next engine
	// call - copy it if you need to keep it.
	virtual const char *GetPlayerName( int playerIndex ) = 0;

	// Appended for the rootcreep menu scoreboard (build 109).
	// IMPORTANT: append-only - do not reorder, do not touch
	// GAMECLIENTEXPORTS_INTERFACE_VERSION; both cl_dll (CClientExports) and
	// mainui (g_pClient) must compile against this same class layout.
	// index is the 0-based client row mainui walks in CMenuScoreboard::Draw;
	// slot 0 is always empty (engine player slots are 1-based) and reports
	// false. Returned pointees are borrowed from the client dll (engine
	// backed) - stable across frames, invalid after the dll unloads.

	// team_info_t** is passed as-is: Scoreboard.cpp takes the address of its
	// own team_info_t* local and dereferences it right after the call.
	virtual bool GetTeamInfo( int index, struct team_info_t **team ) = 0;

	// Fills the player row for one scoreboard line: engine player info
	// (name/ping/SteamID), the extra HUD info block (frags/team/has_c4/...)
	// and the BOT flag (engine userinfo key "*bot").
	virtual bool GetPlayerExtraInfo( int index, struct hud_player_info_s **player, struct extra_player_info_t **extra, bool *isBot ) = 0;

	// Steam presence broker (avatar/relationship) for one SteamID.
	// Returns an sbrk_player_status_t (SBRK_PLAYER_READY = filled);
	// `steam` points at the caller's own sbrk_player_info_t (void* because
	// the type is a tagless typedef in cdll_int.h - not forward-declarable).
	virtual int  GetPlayerSteamInfo( uint64_t steamid, void *steam ) = 0;

	// A7 build 110: gamedir-relative logo/avatar FILE for one player row,
	// resolved by the legacy HUD scoreboard's chain (friends/avatars.txt,
	// media/avatars/av_<account>.png|tga from *sid, avatar_custom/self for
	// my row, cl_playerlogo logos/ pick). NULL/"" = nothing on disk. Lets
	// every scoreboard row show a picture even without Steam identity.
	virtual const char *GetPlayerLogoPath( int playerIndex ) = 0;
};

#define GAMECLIENTEXPORTS_INTERFACE_VERSION "GameClientExports001"


#endif // IGAMECLIENTEXPORTS_H
