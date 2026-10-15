package com.ywjmoney.jangbu;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/** 결제 예정 알림 예약. 정확한 시각 권한 없이 setAndAllowWhileIdle(수 분 오차 허용)로 예약한다. */
final class Reminders {
    static final String CHANNEL = "upcoming";
    private static final String PREFS = "reminders";
    private static final String KEY = "list";
    private static final int MAX = 60;

    private Reminders() {
    }

    static void ensureChannel(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, ctx.getString(R.string.channel_upcoming), NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("구독·고정 지출·카드 결제일 D-3, 당일 알림");
            nm.createNotificationChannel(ch);
        }
    }

    static synchronized void replaceAll(Context ctx, String json) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        cancel(ctx, prefs.getString(KEY, "[]"));
        JSONArray kept = new JSONArray();
        try {
            JSONArray arr = new JSONArray(json);
            long now = System.currentTimeMillis();
            for (int i = 0; i < arr.length() && kept.length() < MAX; i++) {
                JSONObject o = arr.getJSONObject(i);
                if (o.optLong("at") > now) kept.put(o);
            }
        } catch (Exception ignored) {
            // 형식이 틀리면 예약을 모두 비운다.
        }
        prefs.edit().putString(KEY, kept.toString()).apply();
        schedule(ctx, kept);
    }

    /** 재부팅 뒤 저장해 둔 목록으로 다시 예약한다. */
    static synchronized void restore(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            schedule(ctx, new JSONArray(prefs.getString(KEY, "[]")));
        } catch (Exception ignored) {
        }
    }

    private static void schedule(Context ctx, JSONArray list) {
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        if (am == null) return;
        long now = System.currentTimeMillis();
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.optJSONObject(i);
            if (o == null || o.optLong("at") <= now) continue;
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, o.optLong("at"), pending(ctx, i, o.optString("title"), o.optString("body")));
        }
    }

    private static void cancel(Context ctx, String json) {
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        if (am == null) return;
        try {
            int n = new JSONArray(json).length();
            for (int i = 0; i < n; i++) am.cancel(pending(ctx, i, "", ""));
        } catch (Exception ignored) {
        }
    }

    private static PendingIntent pending(Context ctx, int index, String title, String body) {
        Intent i = new Intent(ctx, ReminderReceiver.class)
                .setAction("com.ywjmoney.jangbu.REMIND." + index)
                .putExtra("title", title)
                .putExtra("body", body)
                .putExtra("id", 1000 + index);
        return PendingIntent.getBroadcast(ctx, index, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
