package com.camfloat.hdmi;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

/** Launcher entry: opens the CCTV window with the remote controls (and asks for the overlay permission if needed). */
public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow \"display over other apps\" for CCTV Window", Toast.LENGTH_LONG).show();
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) {
                // some TV builds have no such screen: it can be granted over adb instead
            }
        } else {
            startForegroundService(new Intent(this, OverlayService.class).setAction(OverlayService.ACTION_OPEN));
        }
        finish();
    }
}
