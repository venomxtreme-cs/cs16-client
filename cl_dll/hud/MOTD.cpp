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
// MOTD.cpp
//
// for displaying a server-sent message of the day
//

#include "hud.h"
#include "cl_util.h"
#include "parsemsg.h"
#include "kbutton.h"
#include "triangleapi.h"
#include <string.h>
#include <stdio.h>
#include <ctype.h>
#include "draw_util.h"
#include "build.h"

#if _WIN32
#define strncasecmp _strnicmp
#endif

//
// The original client shows HTML MOTDs in a VGUI window. We have no VGUI here,
// so approximate it: convert common HTML into plain text with line breaks and
// render it in the text panel instead of dropping the message on the floor.
//

// case-insensitive substring search (portable replacement for strcasestr)
static const char *MOTD_FindIC( const char *s, const char *needle )
{
	for( ; *s; s++ )
	{
		const char *a = s, *b = needle;
		while( *a && *b && tolower((unsigned char)*a) == tolower((unsigned char)*b) ) { a++; b++; }
		if( !*b )
			return s;
	}
	return NULL;
}

static bool MOTD_IsHtml( const char *s )
{
	static const char *tags[] = {
		"<!doctype", "<html", "<head", "<title", "<body", "<br", "<p>", "<p ",
		"<div", "<table", "<font", "<center", "<img", "<span", "<a ", "<b>", "<i>", "<u"
	};
	for( int i = 0; i < (int)( sizeof(tags) / sizeof(tags[0]) ); i++ )
	{
		if( MOTD_FindIC( s, tags[i] ) )
			return true;
	}
	return false;
}

static char MOTD_LastChar( const CUtlString &out )
{
	const char *s = out.String();
	if( !s || !*s )
		return 0;
	return s[strlen(s) - 1];
}

// end current line if it has content on it
static void MOTD_EndLine( CUtlString &out )
{
	char last = MOTD_LastChar( out );
	if( last && last != '\n' )
		out.AppendChar( '\n' );
}

// paragraph break: blank line after current one
static void MOTD_EndParagraph( CUtlString &out )
{
	MOTD_EndLine( out );
	out.AppendChar( '\n' );
}

// decode an html entity at p; returns the decoded char (-1: not a valid
// entity, -2: entity should be dropped), sets *consumed
static int MOTD_DecodeEntity( const char *p, int *consumed )
{
	*consumed = 0;

	if( p[1] == '#' )
	{
		int base = 10;
		const char *s = p + 2;
		if( *s == 'x' || *s == 'X' )
		{
			base = 16;
			s++;
		}
		int code = 0, digits = 0;
		while( isalnum((unsigned char)*s ) )
		{
			int d;
			if( *s >= '0' && *s <= '9' ) d = *s - '0';
			else d = ( tolower((unsigned char)*s ) - 'a' ) + 10;
			if( d >= base ) break;
			code = code * base + d;
			s++;
			if( ++digits > 7 ) break; // absurd value: stop before overflowing
		}
		if( digits && *s == ';' )
		{
			*consumed = (int)( s - p ) + 1;

			// common punctuation the sprite font has no glyphs for:
			// keep the text readable the way the pc client shows it
			switch( code )
			{
			case 0: case 160: case 8194: case 8195: case 8201: return ' ';
			case 169: return 'c';
			case 174: return 'r';
			case 8216: case 8217: return '\'';
			case 8220: case 8221: return '"';
			case 8211: case 8212: return '-';
			case 8230: return '.';
			}

			if( code > 0x7E )
				return -2; // sprite font has no such glyph: drop it

			return code ? code : ' ';
		}
		return -1;
	}

	static const struct { const char *name; char ch; } entities[] = {
		{ "nbsp", ' ' }, { "amp", '&' }, { "lt", '<' }, { "gt", '>' },
		{ "quot", '"' }, { "apos", '\'' }, { "copy", 'c' }, { "reg", 'r' },
		{ "ensp", ' ' }, { "emsp", ' ' }, { "thinsp", ' ' }, { "middot", '.' }
	};
	for( int i = 0; i < (int)( sizeof(entities) / sizeof(entities[0]) ); i++ )
	{
		int nlen = (int)strlen( entities[i].name );
		if( strncasecmp( p + 1, entities[i].name, nlen ) == 0 && p[1 + nlen] == ';' )
		{
			*consumed = nlen + 2;
			return entities[i].ch;
		}
	}
	return -1;
}

static void MOTD_HtmlToText( const char *src, CUtlString &out )
{
	while( *src )
	{
		if( *src == '<' )
		{
			const char *q = src + 1;
			bool closing = false;
			if( *q == '/' )
			{
				closing = true;
				q++;
			}

			// comment or doctype/processing instruction
			if( *q == '!' || *q == '?' )
			{
				if( q[1] == '-' && q[2] == '-' )
				{
					const char *e = strstr( q + 3, "-->" );
					src = e ? e + 3 : src + strlen( src );
				}
				else
				{
					const char *e = strchr( q, '>' );
					src = e ? e + 1 : src + strlen( src );
				}
				continue;
			}

			// tag name
			char tag[32];
			int n = 0;
			while( isalnum((unsigned char)*q ) )
			{
				if( n < (int)sizeof(tag) - 1 )
					tag[n++] = (char)tolower((unsigned char)*q );
				q++;
			}
			tag[n] = 0;
			if( !n )
			{
				// "<3" and friends: not a tag, keep literally
				out.AppendChar( *src );
				src++;
				continue;
			}

			// skip to '>' respecting quotes
			char quote = 0;
			while( *q )
			{
				if( quote )
				{
					if( *q == quote ) quote = 0;
				}
				else if( *q == '"' || *q == '\'' ) quote = *q;
				else if( *q == '>' ) break;
				q++;
			}
			if( !*q )
				break; // malformed tag at end of string
			const char *tagEnd = q + 1;

			// elements whose *content* must be skipped entirely
			if( !closing && ( !strcmp( tag, "script" ) || !strcmp( tag, "style" ) ||
			                  !strcmp( tag, "head" ) || !strcmp( tag, "title" ) ) )
			{
				char closetag[40];
				snprintf( closetag, sizeof( closetag ), "</%s", tag );
				const char *e = MOTD_FindIC( tagEnd, closetag );
				src = e ? strchr( e, '>' ) : NULL;
				if( !src ) src = tagEnd + strlen( tagEnd ); // tolerate unclosed
				else src++;
				continue;
			}

			// line-breaking elements
			if( !strcmp( tag, "br" ) )
			{
				MOTD_EndLine( out );
			}
			else if( !strcmp( tag, "p" ) || !strcmp( tag, "div" ) || !strcmp( tag, "tr" ) ||
			         !strcmp( tag, "li" ) || !strcmp( tag, "hr" ) || !strcmp( tag, "table" ) ||
			         !strcmp( tag, "ul" ) || !strcmp( tag, "pre" ) || !strcmp( tag, "center" ) ||
			         ( tag[0] == 'h' && tag[1] >= '1' && tag[1] <= '6' && tag[2] == 0 ) )
			{
				if( closing ) MOTD_EndParagraph( out );
				else MOTD_EndLine( out );
			}
			else if( !strcmp( tag, "td" ) || !strcmp( tag, "th" ) )
			{
				out.AppendChar( ' ' );
			}
			// everything else (b, i, u, font, a, span, img, html, body, meta, ...): strip tag only

			src = tagEnd;
			continue;
		}

		if( *src == '&' )
		{
			int consumed = 0;
			int ch = MOTD_DecodeEntity( src, &consumed );
			if( consumed )
			{
				if( ch >= 0 ) out.AppendChar( (char)ch ); // negative: drop it
				src += consumed;
				continue;
			}
			// unknown entity: keep literally
		}

		if( *src == '\r' )
		{
			src++;
			continue;
		}
		if( *src == '\n' )
		{
			MOTD_EndLine( out );
			src++;
			continue;
		}
		if( *src == ' ' || *src == '\t' )
		{
			// collapse whitespace runs; no leading spaces on a line
			while( *src == ' ' || *src == '\t' ) src++;
			char last = MOTD_LastChar( out );
			if( last && last != '\n' )
				out.AppendChar( ' ' );
			continue;
		}

		out.AppendChar( *src );
		src++;
	}
}

// break over-long lines at word boundaries so nothing is clipped off-screen
static void MOTD_Wrap( CUtlString &out, int maxChars )
{
	if( maxChars < 20 )
		return;
	const char *s = out.String();
	if( !s || !*s )
		return;

	CUtlString tmp;
	const char *p = s;
	while( *p )
	{
		const char *eol = strchr( p, '\n' );
		int remaining = eol ? (int)( eol - p ) : (int)strlen( p );
		const char *q = p;
		while( remaining > maxChars )
		{
			int brk = 0;
			for( int i = 0; i < maxChars; i++ )
			{
				if( q[i] == ' ' ) brk = i;
			}
			if( brk <= 0 )
				brk = maxChars; // unbreakable word: hard split
			tmp.Append( q, brk );
			tmp.AppendChar( '\n' );
			q += brk;
			while( *q == ' ' ) q++; // drop the space we broke on
			remaining = (int)( ( eol ? eol : q + strlen( q ) ) - q );
		}
		tmp.Append( q, remaining );
		if( eol )
		{
			tmp.AppendChar( '\n' );
			p = eol + 1;
		}
		else break;
	}

	out.Clear();
	out.Append( tmp.String() );
}

int CHudMOTD :: Init( void )
{
	gHUD.AddHudElem( this );

	HOOK_MESSAGE( gHUD.m_MOTD, MOTD );

	cl_hide_motd = CVAR_CREATE("cl_hide_motd", "0", FCVAR_ARCHIVE); // hide motd
	Reset();

	return 1;
}

int CHudMOTD :: VidInit( void )
{
	// Load sprites here
	return 1;
}

void CHudMOTD :: Reset( void )
{
	m_iFlags &= ~HUD_DRAW;  // start out inactive
	m_szMOTD.Clear();
	m_iLines = 0;
	m_bShow = false;
	scroll = 0.0f;
}

#define LINE_HEIGHT  13
#define ROW_GAP  13
#define ROW_RANGE_MIN 30
#define ROW_RANGE_MAX ( ScreenHeight - 100 )
int CHudMOTD :: Draw( float fTime )
{
	gHUD.m_iNoConsolePrint &= ~( 1 << 1 );
	if( !m_bShow )
		return 1;

	if( cl_hide_motd->value )
	{
		Reset();
		return 1;
	}

	gHUD.m_iNoConsolePrint |= 1 << 1;
	// find the top of where the MOTD should be drawn,  so the whole thing is centered in the screen
	int ypos = (ScreenHeight - LINE_HEIGHT * m_iLines)/2; // shift it up slightly
	char *ch = m_szMOTD.Access();
	int xpos = (ScreenWidth - gHUD.GetCharWidth( 'M' ) * m_iMaxLength) / 2;
	if( xpos < 30 ) xpos = 30;
	int xmax = xpos + gHUD.GetCharWidth( 'M' ) * m_iMaxLength;
	int height = LINE_HEIGHT * m_iLines;
	int ypos_r=ypos;
	if( height > ROW_RANGE_MAX )
	{
		ypos = ROW_RANGE_MIN + 7 + scroll;
		if( ypos  > ROW_RANGE_MIN + 4 )
			scroll-= (ypos - ( ROW_RANGE_MIN + 4))/3.0;
		if( ypos + height < ROW_RANGE_MAX )
			scroll+= (ROW_RANGE_MAX - (ypos + height))/ 3.0;
		ypos_r = ROW_RANGE_MIN;
		height = ROW_RANGE_MAX;
	}
	if( xmax > ScreenWidth - 30 ) xmax = ScreenWidth - 30;
	char *next_line;
	DrawUtils::DrawRectangle(xpos-5, ypos_r - 5, xmax - xpos+10, height + 10);
	while ( *ch )
	{
		int line_length = 0;  // count the length of the current line
		for ( next_line = ch; *next_line != '\n' && *next_line != 0; next_line++ )
			line_length += gHUD.GetCharWidth( (unsigned char)*next_line );
		char *top = next_line;
		if ( *top == '\n' )
			*top = 0;
		else
			top = NULL;

		// find where to start drawing the line
		if( (ypos > ROW_RANGE_MIN) && (ypos + LINE_HEIGHT <= ypos_r + height) )
			DrawUtils::DrawHudString( xpos, ypos, xmax, ch, 255, 180, 0 );

		ypos += LINE_HEIGHT;

		if ( top )  // restore 
			*top = '\n';
		ch = next_line;
		if ( *ch == '\n' )
			ch++;

		if ( ypos > (ScreenHeight - 20) )
			break;  // don't let it draw too low
	}
	
	return 1;
}

int CHudMOTD :: MsgFunc_MOTD( const char *pszName, int iSize, void *pbuf )
{
	if( cl_hide_motd->value )
		return 1;

	if ( m_iFlags & HUD_DRAW )
	{
		Reset(); // clear the current MOTD in prep for this one
	}

	BufferReader reader( pszName, pbuf, iSize );

	int is_finished = reader.ReadByte();
	m_szMOTD.Append( reader.ReadString() );

	if ( is_finished )
	{
		// convert HTML to plain text so formatted server MOTDs actually show,
		// matching what the original client does with its VGUI HTML window
		if( MOTD_IsHtml( m_szMOTD.String() ) )
		{
			CUtlString text;
			MOTD_HtmlToText( m_szMOTD.String(), text );

			// wrap long lines to the usable screen width
			int charw = gHUD.GetCharWidth( 'M' );
			int maxChars = ( ScreenWidth - 60 ) / ( charw > 0 ? charw : 10 );
			MOTD_Wrap( text, maxChars );

			m_szMOTD.Clear();
			m_szMOTD.Append( text.String() ? text.String() : "" );
		}

		int length = 0;
		
		m_iMaxLength = 0;
		m_iFlags |= HUD_DRAW;


		for ( const char *sz = m_szMOTD.String(); *sz != 0; sz++ )  // count the number of lines in the MOTD
		{
			if ( *sz == '\n' )
			{
				m_iLines++;
				if( length > m_iMaxLength )
				{
					m_iMaxLength = length;
					length = 0;
				}
			}
			length++;
		}
		
		m_iLines++;
		if( length > m_iMaxLength )
		{
			m_iMaxLength = length;
			length = 0;
		}
		m_bShow = true;
	}

	return 1;
}
