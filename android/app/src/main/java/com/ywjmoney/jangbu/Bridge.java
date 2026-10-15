package com.ywjmoney.jangbu;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** 웹 화면에서 window.JangbuAndroid 로 부르는 기능들. */
public class Bridge {
    private final Activity activity;
    private final WebView web;

    Bridge(Activity activity, WebView web) {
        this.activity = activity;
        this.web = web;
    }

    @JavascriptInterface
    public String appVersion() {
        try {
            return activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    /** CSV·백업·.ics 파일을 만들고, .ics는 달력 앱으로 열고 나머지는 공유 창(드라이브·파일·메일 등)으로 보낸다. */
    @JavascriptInterface
    public void saveFile(String name, String mime, String content) {
        try {
            File dir = new File(activity.getCacheDir(), "exports");
            if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("폴더를 만들 수 없음");
            File file = new File(dir, name.replaceAll("[\\\\/:*?\"<>|]", "_"));
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(content.getBytes(StandardCharsets.UTF_8));
            }
            final Uri uri = ExportProvider.uriFor(file);
            final String type = mime.split(";")[0].trim();
            final String title = name;
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                if ("text/calendar".equals(type)) {
                    Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    try {
                        activity.startActivity(view);
                        return;
                    } catch (ActivityNotFoundException ignored) {
                        // 달력 앱이 .ics를 열지 못하면 공유 창으로 넘긴다.
                    }
                }
                Intent send = new Intent(Intent.ACTION_SEND)
                        .setType(type)
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .putExtra(Intent.EXTRA_SUBJECT, title)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                send.setClipData(ClipData.newRawUri(title, uri));
                activity.startActivity(Intent.createChooser(send, title + " 저장·보내기"));
                }
            });
        } catch (Exception e) {
            toast("파일을 만들지 못했어요");
        }
    }

    @JavascriptInterface
    public String readClipboard() {
        ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip() == null || cm.getPrimaryClip().getItemCount() == 0) return "";
        CharSequence t = cm.getPrimaryClip().getItemAt(0).coerceToText(activity);
        return t == null ? "" : t.toString();
    }

    @JavascriptInterface
    public boolean notificationsEnabled() {
        if (Build.VERSION.SDK_INT >= 33
                && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        NotificationManager nm = activity.getSystemService(NotificationManager.class);
        return nm != null && nm.areNotificationsEnabled();
    }

    @JavascriptInterface
    public void requestNotifications() {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
            if (Build.VERSION.SDK_INT >= 33
                    && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
            } else {
                // 이미 허용했거나 설정에서 꺼 둔 경우: 앱 알림 설정 화면을 연다.
                Intent i = new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, activity.getPackageName());
                try {
                    activity.startActivity(i);
                } catch (ActivityNotFoundException ignored) {
                }
            }
            }
        });
    }

    /** [{id,title,body,at(ms)}] 목록으로 예약 알림을 통째로 바꾼다. 앱이 꺼져 있어도 울린다. */
    @JavascriptInterface
    public void scheduleReminders(String json) {
        Reminders.replaceAll(activity, json);
    }

    // ── 결제 알림 자동 기록 ──────────────────────────────────────
    /** {enabled, connected, lastEventAt, lastCaptureAt, total, pending} */
    @JavascriptInterface
    public String captureStatus() {
        return CaptureStore.status(activity);
    }

    /** 아직 화면이 처리하지 않은 알림 문구 [{id, pkg, app, text, at}] */
    @JavascriptInterface
    public String pullCaptured() {
        return CaptureStore.pull(activity);
    }

    @JavascriptInterface
    public void ackCaptured(String idsJson) {
        CaptureStore.ack(activity, idsJson);
    }

    @JavascriptInterface
    public void openCaptureSettings() {
        startSettings(new Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
    }

    /** 제조사 절전 기능이 수집을 끊을 때: 배터리 최적화 예외 목록 화면을 연다. */
    @JavascriptInterface
    public void openBatterySettings() {
        startSettings(new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
    }

    /** 못 읽은 문자 형식 제보 등 텍스트를 다른 앱으로 보낸다(사용자가 받는 곳을 고른다). */
    @JavascriptInterface
    public void shareText(final String subject, final String text) {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, subject)
                        .putExtra(Intent.EXTRA_TEXT, text);
                activity.startActivity(Intent.createChooser(send, subject));
            }
        });
    }

    private void startSettings(final Intent i) {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    activity.startActivity(i);
                } catch (ActivityNotFoundException e) {
                    try {
                        activity.startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS));
                    } catch (ActivityNotFoundException ignored) {
                    }
                }
            }
        });
    }

    @JavascriptInterface
    public void toast(final String msg) {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    @JavascriptInterface
    public void openUrl(final String url) {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (ActivityNotFoundException ignored) {
                }
            }
        });
    }
}
