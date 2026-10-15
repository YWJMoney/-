package com.ywjmoney.jangbu;

import android.app.Notification;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.List;

/**
 * 결제 알림 자동 기록. 사용자가 "알림 접근"을 허용하면 카드사·은행·페이 앱 알림과 문자 앱 알림을 받아,
 * 결제처럼 보이는 문구만 CaptureStore에 넘긴다. 다른 알림은 읽고 바로 버리며 저장하지 않는다.
 */
public class CaptureService extends NotificationListenerService {
    /** 앱 화면이 열려 있으면 새 항목이 왔다고 알려 줄 콜백(메인 스레드에서 실행). */
    static volatile Runnable onCaptured;
    private static long lastEventMark;

    @Override
    public void onListenerConnected() {
        CaptureStore.markConnected(this, true);
    }

    @Override
    public void onListenerDisconnected() {
        CaptureStore.markConnected(this, false);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        long now = System.currentTimeMillis();
        // 수집이 살아 있는지 점검용 기록. 너무 자주 쓰지 않도록 10분에 한 번만 남긴다.
        if (now - lastEventMark > 10 * 60 * 1000) {
            lastEventMark = now;
            CaptureStore.markEvent(this, now);
        }
        if (sbn == null || getPackageName().equals(sbn.getPackageName())) return;
        Notification n = sbn.getNotification();
        if (n == null || (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0 || (n.flags & Notification.FLAG_ONGOING_EVENT) != 0) return;

        Bundle ex = n.extras;
        if (ex == null) return;
        String title = str(ex.getCharSequence(Notification.EXTRA_TITLE));
        List<String> bodies = new ArrayList<>();
        Parcelable[] msgs = ex.getParcelableArray(Notification.EXTRA_MESSAGES);
        if (msgs != null && msgs.length > 0) {
            // 문자 앱의 대화형 알림: 쌓인 메시지를 한 건씩 본다(이미 본 문구는 CaptureStore가 거른다).
            for (Parcelable m : msgs) {
                if (m instanceof Bundle) bodies.add(str(((Bundle) m).getCharSequence("text")));
            }
        } else {
            String big = str(ex.getCharSequence(Notification.EXTRA_BIG_TEXT));
            bodies.add(big.isEmpty() ? str(ex.getCharSequence(Notification.EXTRA_TEXT)) : big);
        }

        boolean added = false;
        String app = appLabel(sbn.getPackageName());
        for (String body : bodies) {
            String full = title + "\n" + body;
            if (!CaptureStore.looksFinancial(full)) continue;
            if (CaptureStore.add(this, sbn.getPackageName(), app, title, body, sbn.getPostTime())) added = true;
        }
        Runnable cb = onCaptured;
        if (added && cb != null) new Handler(Looper.getMainLooper()).post(cb);
    }

    private String appLabel(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return pm.getApplicationLabel(ai).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private static String str(CharSequence cs) {
        return cs == null ? "" : cs.toString().trim();
    }
}
