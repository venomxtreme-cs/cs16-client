/***
*
*	Copyright (c) 1996-2002, Valve LLC. All rights reserved.
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
//  hud_update.cpp
//

#include <math.h>
#include "hud.h"
#include "cl_util.h"
#include <stdlib.h>
#include <memory.h>

int CL_ButtonBits( int );
void CL_ResetButtonBits( int bits );

extern float v_idlescale;
extern void HUD_SetCmdBits( int bits );

int CHud::UpdateClientData(client_data_t *cdata, float time)
{
	m_vecOrigin = cdata->origin;
	m_vecAngles = cdata->viewangles;

	m_iKeyBits = CL_ButtonBits( 0 );
	m_iWeaponBits = cdata->iWeaponBits;

	Think();

	// fov_angle (70-100) overrides the base FOV client-side; server zoom values always win
	float targetFov = (float)m_iFOV;

	if ( fov_angle->value >= 70.0f && fov_angle->value <= 100.0f && m_iFOV >= 70 )
		targetFov = fov_angle->value;

	// fov_lerp (seconds) smooths FOV transitions, e.g. on zoom in/out; 0 - instant
	if ( fov_lerp->value > 0.0f && m_flCurrentFOV > 0.0f )
	{
		float flerp = fov_lerp->value;

		if ( flerp < 0.01f )
			flerp = 0.01f;

		m_flCurrentFOV += ( targetFov - m_flCurrentFOV ) * (float)( m_flTimeDelta / flerp );

		if ( m_flCurrentFOV < 0.1f )
			m_flCurrentFOV = 0.1f;
	}
	else m_flCurrentFOV = targetFov;

	cdata->fov = m_flCurrentFOV;
	
	v_idlescale = m_iConcussionEffect;

	CL_ResetButtonBits( m_iKeyBits );

	// return 1 if in anything in the client_data struct has been changed, 0 otherwise
	return 1;
}


