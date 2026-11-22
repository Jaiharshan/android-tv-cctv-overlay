package com.camfloat.hdmi;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

/** After the TV boots, starts the idle service so the remote shortcut works. Nothing is shown. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (Settings.canDrawOverlays(ctx)) {
            ctx.startForegroundService(new Intent(ctx, OverlayService.class).setAction(OverlayService.ACTION_IDLE));
        }
    }
}
