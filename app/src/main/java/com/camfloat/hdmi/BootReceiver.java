package com.camfloat.hdmi;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

/** Brings the floating window back after the TV boots. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (Settings.canDrawOverlays(ctx)) {
            ctx.startForegroundService(new Intent(ctx, OverlayService.class).setAction(OverlayService.ACTION_SHOW));
        }
    }
}
