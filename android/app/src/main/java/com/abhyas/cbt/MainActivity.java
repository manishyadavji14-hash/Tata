package com.abhyas.cbt;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import android.database.Cursor;
import android.provider.OpenableColumns;
import java.util.ArrayList;
import java.util.List;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Thin WebView shell around the offline web app in assets/.
 * Assets are served from https://appassets.androidplatform.net/ (a secure origin, so IndexedDB,
 * ES modules and the clipboard work). A small JS bridge adds things a WebView lacks:
 * file saving, clipboard, CORS-free HTTP for AI APIs, keep-screen-on and the back button.
 */
public class MainActivity extends Activity {
    static final String HOST = "appassets.androidplatform.net";
    static final String START_URL = "https://" + HOST + "/index.html";
    private static final int FILE_CHOOSER = 1001;

    private WebView web;
    private FrameLayout root;
    private ValueCallback<Uri[]> fileCallback;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final Map<String, String> responses = new ConcurrentHashMap<>();
    private final Map<String, HttpURLConnection> running = new ConcurrentHashMap<>();
    private final List<JSONObject> incoming = new ArrayList<>();
    private volatile boolean pageReady = false;
    private boolean openJobsOnLoad = false;
    private boolean askedNotif = false;

    private static final Map<String, String> MIME = new HashMap<>();
    static {
        MIME.put("html", "text/html"); MIME.put("js", "text/javascript"); MIME.put("mjs", "text/javascript");
        MIME.put("css", "text/css"); MIME.put("json", "application/json"); MIME.put("webmanifest", "application/manifest+json");
        MIME.put("png", "image/png"); MIME.put("svg", "image/svg+xml"); MIME.put("woff2", "font/woff2");
        MIME.put("bcmap", "application/octet-stream"); MIME.put("pfb", "application/octet-stream"); MIME.put("ttf", "font/ttf");
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#1b5ef5"));
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        applyInsets();

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (!HOST.equals(u.getHost())) return null;
                String path = u.getPath() == null ? "" : u.getPath();
                if (path.startsWith("/")) path = path.substring(1);
                if (path.isEmpty()) path = "index.html";
                if (path.contains("..")) return notFound();
                try {
                    InputStream in = getAssets().open(path);
                    String ext = path.substring(path.lastIndexOf('.') + 1).toLowerCase();
                    String mime = MIME.containsKey(ext) ? MIME.get(ext) : "application/octet-stream";
                    WebResourceResponse r = new WebResourceResponse(mime, mime.startsWith("text/") || mime.contains("json") ? "utf-8" : null, in);
                    Map<String, String> h = new HashMap<>();
                    h.put("Cache-Control", "no-cache");
                    r.setResponseHeaders(h);
                    return r;
                } catch (IOException e) {
                    return notFound();
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                notifyIncoming();
                if (openJobsOnLoad) {
                    openJobsOnLoad = false;
                    view.postDelayed(() -> web.evaluateJavascript("window.__abhyasOpenJobs && window.__abhyasOpenJobs()", null), 800);
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (HOST.equals(u.getHost())) return false;
                // Never navigate the app WebView to an external site: open it in the browser instead.
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                try {
                    Intent i = params.createIntent();
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    String[] types = params.getAcceptTypes();
                    boolean wantsJson = false;
                    for (String t : types) if (t.contains("json")) wantsJson = true;
                    // Many file managers label .json as octet-stream/plain; allow any file for JSON pickers.
                    i.setType(wantsJson ? "*/*" : (types.length > 0 && !types[0].isEmpty() ? (types[0].startsWith(".") ? "*/*" : types[0]) : "*/*"));
                    if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    startActivityForResult(Intent.createChooser(i, "फ़ाइल चुनें"), FILE_CHOOSER);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });
        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(START_URL);
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    /** A test file opened or shared into the app → read it and hand it to the web app. */
    private void handleIntent(Intent intent) {
        if (intent == null) return;
        if ("jobs".equals(intent.getStringExtra("abhyas_open"))) {
            intent.removeExtra("abhyas_open");
            if (pageReady) web.evaluateJavascript("window.__abhyasOpenJobs && window.__abhyasOpenJobs()", null);
            else openJobsOnLoad = true;
            return;
        }
        String action = intent.getAction();
        List<Uri> uris = new ArrayList<>();
        String text = null;
        if (Intent.ACTION_VIEW.equals(action) && intent.getData() != null) uris.add(intent.getData());
        else if (Intent.ACTION_SEND.equals(action)) {
            Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u); else text = intent.getStringExtra(Intent.EXTRA_TEXT);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) uris.addAll(list);
        } else return;
        final String sharedText = text;
        intent.setAction(Intent.ACTION_MAIN); // do not import again after rotation
        pool.execute(() -> {
            int added = 0;
            for (Uri u : uris) {
                try (InputStream in = getContentResolver().openInputStream(u)) {
                    if (in == null) continue;
                    String body = readAll(in);
                    JSONObject o = new JSONObject();
                    o.put("name", displayName(u));
                    o.put("text", body);
                    synchronized (incoming) { incoming.add(o); }
                    added++;
                } catch (Exception e) { toast("फ़ाइल नहीं पढ़ सके: " + e.getMessage()); }
            }
            if (sharedText != null && sharedText.trim().startsWith("{")) {
                try {
                    JSONObject o = new JSONObject();
                    o.put("name", "Shared text");
                    o.put("text", sharedText);
                    synchronized (incoming) { incoming.add(o); }
                    added++;
                } catch (Exception ignored) { }
            }
            if (added > 0) runOnUiThread(this::notifyIncoming);
        });
    }

    private String displayName(Uri u) {
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) { String n = c.getString(0); if (n != null) return n; }
        } catch (Exception ignored) { }
        String seg = u.getLastPathSegment();
        return seg == null ? "shared.json" : seg;
    }

    private void notifyIncoming() {
        boolean has;
        synchronized (incoming) { has = !incoming.isEmpty(); }
        if (has && pageReady && web != null) web.evaluateJavascript("window.__abhyasIncomingReady && window.__abhyasIncomingReady()", null);
    }

    private static WebResourceResponse notFound() {
        WebResourceResponse r = new WebResourceResponse("text/plain", "utf-8", new java.io.ByteArrayInputStream(new byte[0]));
        r.setStatusCodeAndReasonPhrase(404, "Not Found");
        return r;
    }

    // Keep content clear of the status/navigation bars (Android 15 enforces edge-to-edge).
    private void applyInsets() {
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, bottom, left, right;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                top = i.top; bottom = i.bottom; left = i.left; right = i.right;
            } else {
                top = insets.getSystemWindowInsetTop(); bottom = insets.getSystemWindowInsetBottom();
                left = insets.getSystemWindowInsetLeft(); right = insets.getSystemWindowInsetRight();
            }
            v.setPadding(left, top, right, bottom);
            return insets;
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != FILE_CHOOSER || fileCallback == null) return;
        Uri[] result = null;
        if (resultCode == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                ClipData c = data.getClipData();
                result = new Uri[c.getItemCount()];
                for (int i = 0; i < c.getItemCount(); i++) result[i] = c.getItemAt(i).getUri();
            } else if (data.getData() != null) {
                result = new Uri[]{data.getData()};
            }
        }
        fileCallback.onReceiveValue(result);
        fileCallback = null;
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("(window.__abhyasBack && window.__abhyasBack()) ? 'y' : 'n'", value -> {
            if (value == null || !value.contains("y")) MainActivity.super.onBackPressed();
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onDestroy() {
        try { stopService(new Intent(this, KeepAliveService.class)); } catch (Exception ignored) { }
        pool.shutdownNow();
        if (web != null) web.destroy();
        super.onDestroy();
    }

    /** Methods callable from JavaScript as window.AndroidBridge.* */
    class Bridge {
        @JavascriptInterface
        public String saveFile(String name, String content, String mime) {
            String safe = name.replaceAll("[^\\p{L}\\p{N}._ -]", "_");
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Downloads.DISPLAY_NAME, safe);
                    v.put(MediaStore.Downloads.MIME_TYPE, mime == null || mime.isEmpty() ? "application/json" : mime);
                    v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AbhyasCBT");
                    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    if (uri == null) throw new IOException("MediaStore insert failed");
                    try (OutputStream os = getContentResolver().openOutputStream(uri)) { os.write(bytes); }
                    toast("सेव हुआ: Download/AbhyasCBT/" + safe);
                    return "Download/AbhyasCBT/" + safe;
                } else {
                    File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    File f = new File(dir, safe);
                    try (FileOutputStream os = new FileOutputStream(f)) { os.write(bytes); }
                    toast("सेव हुआ: " + f.getAbsolutePath());
                    return f.getAbsolutePath();
                }
            } catch (Exception e) {
                toast("सेव नहीं हुआ: " + e.getMessage());
                return "";
            }
        }

        /** count > 0: keep the app alive with an ongoing notification; 0: stop. */
        @JavascriptInterface
        public void setBackgroundWork(int count, String text) {
            runOnUiThread(() -> {
                try {
                    if (count <= 0) {
                        if (KeepAliveService.running) stopService(new Intent(MainActivity.this, KeepAliveService.class));
                        return;
                    }
                    if (Build.VERSION.SDK_INT >= 33 && !askedNotif
                            && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        askedNotif = true;
                        requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 77);
                    }
                    if (KeepAliveService.running) { KeepAliveService.update(MainActivity.this, count, text); return; }
                    Intent i = new Intent(MainActivity.this, KeepAliveService.class);
                    i.putExtra("count", count);
                    i.putExtra("text", text);
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
                } catch (Exception e) {
                    // Android may refuse to start a service from the background; jobs still run while the app lives.
                }
            });
        }

        @JavascriptInterface
        public void notifyDone(String title, String text) {
            try { KeepAliveService.notifyDone(MainActivity.this, title, text); } catch (Exception ignored) { }
        }

        @JavascriptInterface
        public boolean hasIncoming() {
            synchronized (incoming) { return !incoming.isEmpty(); }
        }

        @JavascriptInterface
        public String takeIncoming() {
            JSONArray a = new JSONArray();
            synchronized (incoming) { for (JSONObject o : incoming) a.put(o); incoming.clear(); }
            return a.toString();
        }

        /** Share a test file through the Android share sheet (Telegram, WhatsApp, Drive, Bluetooth…). */
        @JavascriptInterface
        public void shareFile(String name, String content, String mime, String message) {
            String safe = name.replaceAll("[^\\p{L}\\p{N}._-]", "_");
            if (!safe.toLowerCase().endsWith(".json")) safe += ".json";
            try {
                File f = new File(ShareProvider.dir(MainActivity.this), safe);
                try (FileOutputStream os = new FileOutputStream(f)) { os.write(content.getBytes(StandardCharsets.UTF_8)); }
                Uri uri = Uri.parse("content://" + ShareProvider.AUTHORITY + "/" + Uri.encode(safe));
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType(mime == null || mime.isEmpty() ? "application/json" : mime);
                send.putExtra(Intent.EXTRA_STREAM, uri);
                if (message != null && !message.isEmpty()) send.putExtra(Intent.EXTRA_TEXT, message);
                send.putExtra(Intent.EXTRA_SUBJECT, safe);
                send.setClipData(ClipData.newRawUri(safe, uri));
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Intent chooser = Intent.createChooser(send, "टेस्ट शेयर करें");
                chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                runOnUiThread(() -> startActivity(chooser));
            } catch (Exception e) {
                toast("शेयर नहीं हुआ: " + e.getMessage());
            }
        }

        @JavascriptInterface
        public void openUrl(String url) {
            if (url == null || !(url.startsWith("https://") || url.startsWith("http://") || url.startsWith("tg://"))) return;
            runOnUiThread(() -> {
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
                catch (Exception e) { toast("लिंक नहीं खुला: " + url); }
            });
        }

        @JavascriptInterface
        public void copyText(String text) {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("Abhyas", text));
        }

        @JavascriptInterface
        public void keepScreenOn(boolean on) {
            runOnUiThread(() -> {
                Window w = getWindow();
                if (on) w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }

        @JavascriptInterface
        public void setDarkMode(boolean dark) {
            runOnUiThread(() -> {
                root.setBackgroundColor(Color.parseColor(dark ? "#020617" : "#1b5ef5"));
                if (Build.VERSION.SDK_INT < 35) {
                    getWindow().setStatusBarColor(Color.parseColor(dark ? "#020617" : "#1b5ef5"));
                    getWindow().setNavigationBarColor(Color.parseColor(dark ? "#020617" : "#ffffff"));
                }
                if (Build.VERSION.SDK_INT >= 26) {
                    View d = getWindow().getDecorView();
                    int flags = d.getSystemUiVisibility();
                    if (dark) flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                    else flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                    d.setSystemUiVisibility(flags);
                }
            });
        }

        /** Asynchronous HTTP without CORS limits (AI API calls). Result is fetched with takeResponse(id). */
        @JavascriptInterface
        public void httpRequest(String id, String url, String method, String headersJson, String body) {
            pool.execute(() -> {
                JSONObject out = new JSONObject();
                HttpURLConnection c = null;
                try {
                    URL u = new URL(url);
                    String proto = u.getProtocol();
                    if (!"https".equals(proto) && !"http".equals(proto)) throw new IOException("Only http(s) URLs are allowed");
                    c = (HttpURLConnection) u.openConnection();
                    running.put(id, c);
                    c.setRequestMethod(method == null || method.isEmpty() ? "POST" : method);
                    c.setConnectTimeout(30000);
                    c.setReadTimeout(300000);
                    JSONObject h = new JSONObject(headersJson == null || headersJson.isEmpty() ? "{}" : headersJson);
                    Iterator<String> keys = h.keys();
                    while (keys.hasNext()) { String k = keys.next(); c.setRequestProperty(k, h.getString(k)); }
                    if (body != null && !body.isEmpty()) {
                        c.setDoOutput(true);
                        byte[] b = body.getBytes(StandardCharsets.UTF_8);
                        c.setFixedLengthStreamingMode(b.length);
                        try (OutputStream os = c.getOutputStream()) { os.write(b); }
                    }
                    int status = c.getResponseCode();
                    InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
                    out.put("status", status);
                    out.put("body", in == null ? "" : readAll(in));
                } catch (Exception e) {
                    try { out.put("error", "Network error: " + e.getMessage()); } catch (Exception ignored) { }
                } finally {
                    running.remove(id);
                    if (c != null) c.disconnect();
                }
                responses.put(id, out.toString());
                runOnUiThread(() -> web.evaluateJavascript("window.__abhyasHttpDone && window.__abhyasHttpDone(" + JSONObject.quote(id) + ")", null));
            });
        }

        @JavascriptInterface
        public String takeResponse(String id) {
            String r = responses.remove(id);
            return r == null ? "{\"error\":\"No response\"}" : r;
        }

        @JavascriptInterface
        public void cancelRequest(String id) {
            HttpURLConnection c = running.remove(id);
            if (c != null) pool.execute(c::disconnect);
            responses.remove(id);
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toString("UTF-8");
    }

    private void toast(String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
    }
}
