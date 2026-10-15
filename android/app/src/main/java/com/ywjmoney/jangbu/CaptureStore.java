package com.ywjmoney.jangbu;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 알림에서 건진 결제 문구를 화면(JS)이 가져갈 때까지 기기 안에 잠시 보관한다.
 * 결제처럼 보이는 문구만 남기고, 화면이 읽어 기록으로 바꾸면 바로 지운다.
 */
final class CaptureStore {
    private static final String PREFS = "capture";
    private static final int MAX_QUEUE = 300;
    private static final int MAX_SEEN = 300;
    private static final long SEEN_TTL = 3L * 24 * 60 * 60 * 1000;

    private static final Pattern AMOUNT = Pattern.compile("\\d[\\d,]*\\s*원|(USD|JPY|EUR|CNY|GBP)\\s*[\\d,.]+|[\\d,.]+\\s*(USD|JPY|EUR|CNY|GBP)");
    private static final Pattern KEYWORD = Pattern.compile("승인|취소|결제|출금|입금|이체|사용|일시불|할부");
    private static final Pattern AD = Pattern.compile("\\(광고\\)|\\[광고\\]|수신거부|무료거부");

    private CaptureStore() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 결제·입출금 알림처럼 보이는지. 광고성 문구는 거른다. */
    static boolean looksFinancial(String text) {
        if (text == null || text.length() < 6 || text.length() > 1000) return false;
        return AMOUNT.matcher(text).find() && KEYWORD.matcher(text).find() && !AD.matcher(text).find();
    }

    static synchronized boolean add(Context c, String pkg, String app, String title, String body, long postedAt) {
        SharedPreferences p = prefs(c);
        try {
            long now = System.currentTimeMillis();
            String hash = Integer.toHexString(body.replaceAll("\\s+", "").hashCode());
            JSONArray seen = new JSONArray(p.getString("seen", "[]"));
            JSONArray keep = new JSONArray();
            for (int i = 0; i < seen.length(); i++) {
                JSONObject s = seen.getJSONObject(i);
                if (now - s.optLong("at") > SEEN_TTL) continue;
                if (hash.equals(s.optString("h"))) return false; // 같은 문구가 다시 올라온 알림(대화 갱신 등)
                keep.put(s);
            }
            JSONObject mark = new JSONObject().put("h", hash).put("at", now);
            keep.put(mark);
            while (keep.length() > MAX_SEEN) keep.remove(0);

            JSONArray queue = new JSONArray(p.getString("queue", "[]"));
            String text = title != null && !title.isEmpty() && !body.contains(title) ? title + "\n" + body : body;
            queue.put(new JSONObject()
                    .put("id", Long.toString(postedAt, 36) + hash)
                    .put("pkg", pkg)
                    .put("app", app)
                    .put("text", text)
                    .put("at", postedAt));
            while (queue.length() > MAX_QUEUE) queue.remove(0);

            p.edit()
                    .putString("seen", keep.toString())
                    .putString("queue", queue.toString())
                    .putLong("lastCaptureAt", now)
                    .putInt("total", p.getInt("total", 0) + 1)
                    .apply();
            return true;
        } catch (JSONException e) {
            p.edit().remove("seen").remove("queue").apply();
            return false;
        }
    }

    static synchronized String pull(Context c) {
        return prefs(c).getString("queue", "[]");
    }

    /** 화면이 처리한 항목을 지운다(원문을 오래 남기지 않는다). */
    static synchronized void ack(Context c, String idsJson) {
        SharedPreferences p = prefs(c);
        try {
            JSONArray ids = new JSONArray(idsJson);
            Set<String> done = new HashSet<>();
            for (int i = 0; i < ids.length(); i++) done.add(ids.getString(i));
            JSONArray queue = new JSONArray(p.getString("queue", "[]"));
            JSONArray keep = new JSONArray();
            for (int i = 0; i < queue.length(); i++) {
                JSONObject q = queue.getJSONObject(i);
                if (!done.contains(q.optString("id"))) keep.put(q);
            }
            p.edit().putString("queue", keep.toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    static void markEvent(Context c, long now) {
        prefs(c).edit().putLong("lastEventAt", now).apply();
    }

    static void markConnected(Context c, boolean connected) {
        prefs(c).edit().putBoolean("connected", connected).putLong("connectedAt", System.currentTimeMillis()).apply();
    }

    static boolean isEnabled(Context c) {
        String flat = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        if (flat == null) return false;
        String me = new ComponentName(c, CaptureService.class).flattenToString();
        for (String s : flat.split(":")) {
            if (me.equals(s) || s.startsWith(c.getPackageName() + "/")) return true;
        }
        return false;
    }

    static String status(Context c) {
        SharedPreferences p = prefs(c);
        try {
            int pending = new JSONArray(p.getString("queue", "[]")).length();
            return new JSONObject()
                    .put("enabled", isEnabled(c))
                    .put("connected", p.getBoolean("connected", false))
                    .put("connectedAt", p.getLong("connectedAt", 0))
                    .put("lastEventAt", p.getLong("lastEventAt", 0))
                    .put("lastCaptureAt", p.getLong("lastCaptureAt", 0))
                    .put("total", p.getInt("total", 0))
                    .put("pending", pending)
                    .toString();
        } catch (JSONException e) {
            return "{}";
        }
    }
}
