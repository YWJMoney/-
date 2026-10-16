package com.ywjmoney.jangbu;

import android.content.res.AssetManager;
import android.net.Uri;
import android.webkit.WebResourceResponse;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * https://appassets.androidplatform.net/assets/www/... 요청을 앱 안 assets 파일로 응답한다.
 * file:// 대신 https 주소를 쓰면 ES 모듈과 localStorage가 정상 동작한다. 네트워크는 쓰지 않는다.
 */
final class AssetServer {
    static final String HOST = "appassets.androidplatform.net";
    private static final String PREFIX = "/assets/";
    private static final Map<String, String> TYPES = new HashMap<>();

    static {
        TYPES.put("html", "text/html");
        TYPES.put("js", "text/javascript");
        TYPES.put("mjs", "text/javascript");
        TYPES.put("css", "text/css");
        TYPES.put("json", "application/json");
        TYPES.put("webmanifest", "application/manifest+json");
        TYPES.put("svg", "image/svg+xml");
        TYPES.put("png", "image/png");
        TYPES.put("ico", "image/x-icon");
    }

    private final AssetManager assets;

    AssetServer(AssetManager assets) {
        this.assets = assets;
    }

    WebResourceResponse handle(Uri uri) {
        if (!"https".equals(uri.getScheme()) || !HOST.equals(uri.getHost())) return null;
        String path = uri.getPath();
        if (path == null || !path.startsWith(PREFIX) || path.contains("..")) return notFound();
        String file = path.substring(PREFIX.length());
        try {
            InputStream in = assets.open(file);
            int dot = file.lastIndexOf('.');
            String ext = dot >= 0 ? file.substring(dot + 1).toLowerCase() : "";
            String type = TYPES.containsKey(ext) ? TYPES.get(ext) : "application/octet-stream";
            boolean text = type.startsWith("text/") || type.contains("json") || type.contains("svg");
            WebResourceResponse res = new WebResourceResponse(type, text ? "utf-8" : null, in);
            Map<String, String> headers = new HashMap<>();
            headers.put("Cache-Control", "no-cache");
            res.setResponseHeaders(headers);
            return res;
        } catch (IOException e) {
            return notFound();
        }
    }

    private static WebResourceResponse notFound() {
        WebResourceResponse res = new WebResourceResponse("text/plain", "utf-8", null);
        res.setStatusCodeAndReasonPhrase(404, "Not Found");
        return res;
    }
}
