//========= Copyright (c) 1996-2002, Valve LLC, All rights reserved. ============
//
// Purpose: 
//
// $NoKeywords: $
//=============================================================================

#ifndef IGAMEMENUEXPORTS_H
#define IGAMEMENUEXPORTS_H
#ifdef _WIN32
#pragma once
#endif

#include "interface.h"
#include "mainui/font/FontRenderer.h"
#include "../dlls/cdll_dll.h"

//-----------------------------------------------------------------------------
// Purpose: Exports a set of functions for the game client to interact with the GameUI
//-----------------------------------------------------------------------------

class IGameMenuExports : public IBaseInterface
{
public:
	virtual bool  Initialize( CreateInterfaceFn factory ) = 0;

	virtual const char *L( const char *szStr ) = 0;

	virtual bool  IsActive( void ) = 0;
	virtual bool  IsMainMenuActive( void ) = 0;

	virtual void  Key( int key, int down ) = 0;
	virtual void  MouseMove( int x, int y ) = 0;

	virtual HFont BuildFont( CFontBuilder &builder ) = 0;

	virtual void  GetCharABCWide( HFont font, int ch, int &a, int &b, int &c ) = 0;
	virtual int   GetFontTall( HFont font ) = 0;

	virtual int   GetCharacterWidth(HFont font, int ch, int charH ) = 0;
	
	virtual void  GetTextSize( HFont font, const char *text, int *wide, int *height = 0, int size = -1 ) = 0;
	virtual int	  GetTextHeight( HFont font, const char *text, int size = -1 ) = 0;

	virtual int   DrawCharacter( HFont font, int ch, int x, int y, int charH, const unsigned int color, bool forceAdditive = false ) = 0;

	// A7 build 109: rootcreep menu scoreboard - the old 7-arg positioning
	// version is gone; mainui's udll_int.cpp overrides the no-arg pair
	// (Setup -> UI_VidInitScoreboard, Draw -> UI_DrawScoreboard). Nobody
	// called the 7-arg form. append-only: keep both copies identical.
	virtual void  SetupScoreboard( void ) = 0;
	virtual void  DrawScoreboard( void ) = 0;

	virtual void  DrawSpectatorMenu( void ) = 0;

	virtual void  ShowVGUIMenu( int menuType, int param1, int param2 ) = 0;
	virtual void  HideVGUIMenu( void ) = 0;
};

#define GAMEMENUEXPORTS_INTERFACE_VERSION "GameMenuExports001"

#endif // IGAMEMENUEXPORTS_H
