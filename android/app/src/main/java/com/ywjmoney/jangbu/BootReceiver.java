package com.ywjmoney.jangbu;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 재부팅하면 예약 알림이 지워지므로 다시 예약한다. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) Reminders.restore(ctx);
    }
}
