package com.camfloat.hdmi;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Entry point for adb scripts: {@code adb shell am broadcast -n com.camfloat.hdmi/.ControlReceiver -a ...}.
 * It is protected by android.permission.DUMP, which only the shell (adb) and the system hold, so other
 * apps on the TV cannot add cameras, read anything, or switch key logging on. The service itself is not exported.
 */
public class ControlReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        Intent svc = new Intent(ctx, OverlayService.class).setAction(intent.getAction());
        if (intent.getExtras() != null) svc.putExtras(intent.getExtras());
        ctx.startForegroundService(svc);
    }
}
