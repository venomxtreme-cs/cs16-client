package su.xash.cs16client;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

public class CZeroActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // build 114 merge: the engine is part of this app now - no external
        // su.xash.engine package lookup and no download-page fallback.
        startActivity(new Intent(this, su.xash.engine.XashActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra("gamedir", "czero")
                .putExtra("gamelibdir", getApplicationInfo().nativeLibraryDir)
                .putExtra("argv", "-dev 2 -log")  // no -dll @yapb -- YaPB loads via metamod plugins.ini
                .putExtra("package", getPackageName()));
        finish();
    }
}
