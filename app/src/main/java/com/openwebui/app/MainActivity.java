package com.openwebui.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.IntentFilter;
import android.hardware.biometrics.BiometricPrompt;
import android.media.AudioManager;
import android.os.CancellationSignal;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.JSONArray;
import android.app.KeyguardManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.util.Base64;
import android.view.View;
import android.view.WindowManager;
import android.webkit.*;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Message;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;

public class MainActivity extends Activity {
    public static final String ACTION_NEW_CHAT = "com.openwebui.app.NEW_CHAT";
    private static final int REQ_FILE = 1, REQ_PERM = 2, REQ_START = 3, REQ_AUTH = 4;
    private static final int MAX_SHARE_BYTES = 3 * 1024 * 1024;

    private WebView web;
    private ProgressBar bar;
    private View lockCover;
    private ValueCallback<Uri[]> fileCb;
    private PermissionRequest pendingPerm;
    private SharedPreferences prefs;
    private boolean inForeground = true;
    private boolean unlocked = false;
    private boolean triedFallback = false;
    private long pausedAt = 0;
    private int notifId = 100;
    private Uri pendingShareUri;
    private static final String COMMUNITY_URL = "https://openwebui.com";
    private WebView community;
    private LinearLayout panel;
    private TextView fab;
    private boolean communityActive = false;
    public static final String ACTION_OPEN_CHAT = "com.openwebui.app.OPEN_CHAT";
    private static final String AUTH = "com.openwebui.app.fileprovider";
    private static final String RELEASES_API = "https://api.github.com/repos/Msjcolli93/OpenWebUI-Apk/releases/latest";
    private Uri captureUri;
    private long updateDownloadId = -1;
    private boolean pendingInstall = false;
    private boolean callMode = false;
    private boolean authPending = false;
    private final BroadcastReceiver dlReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            long id = i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            if (id != -1 && id == updateDownloadId) installUpdate();
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("owui", MODE_PRIVATE);
        FrameLayout root = new FrameLayout(this);
        web = new WebView(this);
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        lockCover = new View(this);
        lockCover.setBackgroundResource(R.drawable.splash);
        lockCover.setClickable(true);
        lockCover.setVisibility(View.GONE);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        root.addView(bar, new FrameLayout.LayoutParams(-1, 8));
        buildCommunity(root);
        root.addView(lockCover, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        applyKeepScreenOn();

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        web.addJavascriptInterface(new Object() {
            @JavascriptInterface public void responseDone(String preview, String url) { runOnUiThread(() -> notifyDone(preview, url)); }
        }, "AndroidNotify");
        web.addJavascriptInterface(new Object() {
            @JavascriptInterface public void save(String name, String mime, String b64) { saveBase64(name, mime, b64); }
        }, "AndroidDl");
        CookieManager.getInstance().setAcceptCookie(true);
        final float[] pull = new float[2];
        web.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    pull[0] = ev.getY() < dp(56) ? ev.getY() : -1;
                    pull[1] = 0;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (pull[0] >= 0 && pull[1] == 0 && ev.getY() - pull[0] > dp(150)) {
                        pull[1] = 1;
                        web.reload();
                        Toast.makeText(this, "Refreshing\u2026", Toast.LENGTH_SHORT).show();
                    }
                    break;
            }
            return false;
        });
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if ("owui".equals(u.getScheme())) {
                    triedFallback = false;
                    String base = prefs.getString("url", null);
                    if (base != null) v.loadUrl(base);
                    return true;
                }
                String host = u.getHost();
                if (host != null && (host.equals(hostOf(prefs.getString("url", ""))) || host.equals(hostOf(prefs.getString("fallback", ""))))) return false;
                startActivity(new Intent(Intent.ACTION_VIEW, u));
                return true;
            }
            @Override public void onPageStarted(WebView v, String u, Bitmap f) { bar.setVisibility(View.VISIBLE); }
            @Override public void onPageFinished(WebView v, String u) {
                bar.setVisibility(View.GONE);
                v.evaluateJavascript("(function(){if(window.__owuiWatch)return;window.__owuiWatch=1;var was=false;setInterval(function(){var on=!!document.getElementById('stop-response-button');if(was&&!on&&window.AndroidNotify){var m=document.querySelectorAll('[id^=\"message-\"]');var t=m.length?m[m.length-1].innerText:'';AndroidNotify.responseDone((t||'').trim().substring(0,160),location.href);}was=on;},1000);})();", null);
                if (pendingShareUri != null && u != null && !u.startsWith("about:")) {
                    final Uri share = pendingShareUri;
                    pendingShareUri = null;
                    v.postDelayed(() -> injectFile(share), 2500);
                }
            }
            @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (!r.isForMainFrame()) return;
                String fb = prefs.getString("fallback", "");
                String cur = r.getUrl().toString();
                if (!fb.isEmpty() && !triedFallback && !cur.startsWith(fb)) {
                    triedFallback = true;
                    v.loadUrl(fb);
                    return;
                }
                v.loadDataWithBaseURL(null, errorHtml(), "text/html", "UTF-8", null);
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView v, int p) { bar.setProgress(p); }
            @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                try { startActivityForResult(buildChooser(p), REQ_FILE); }
                catch (Exception e) { fileCb = null; return false; }
                return true;
            }
            @Override public void onPermissionRequest(PermissionRequest req) {
                runOnUiThread(() -> {
                    pendingPerm = req;
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA}, REQ_PERM);
                    else { req.grant(req.getResources()); pendingPerm = null; }
                });
            }
        });
        web.setDownloadListener((url, ua, cd, mime, len) -> handleDownload(web, url, ua, cd, mime));

        askStartupPermissions();
        ensureProfile();
        IntentFilter dlf = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(dlReceiver, dlf, Context.RECEIVER_EXPORTED);
        else registerReceiver(dlReceiver, dlf);

        if (prefs.getBoolean("lock", false)) {
            lockCover.setVisibility(View.VISIBLE);
            requireAuth();
        } else {
            unlocked = true;
        }

        String url = prefs.getString("url", null);
        if (url == null) askUrl("url", "Open WebUI server address", true);
        else if (!handleIntent(getIntent())) web.loadUrl(url);
        checkForUpdates(false);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    /** Returns true if the intent triggered a page load. */
    private boolean handleIntent(Intent in) {
        if (in == null) return false;
        String base = prefs.getString("url", null);
        if (base == null) return false;
        String slash = base.endsWith("/") ? "" : "/";
        String act = in.getAction();
        if (Intent.ACTION_SEND.equals(act) || ACTION_NEW_CHAT.equals(act) || ACTION_OPEN_CHAT.equals(act)) minimizeCommunity();
        if (Intent.ACTION_SEND.equals(act)) {
            Uri stream = in.getParcelableExtra(Intent.EXTRA_STREAM);
            String text = in.getStringExtra(Intent.EXTRA_TEXT);
            if (stream != null) {
                pendingShareUri = stream;
                triedFallback = false;
                web.loadUrl(base);
                return true;
            } else if (text != null) {
                triedFallback = false;
                web.loadUrl(base + slash + "?q=" + Uri.encode(text));
                return true;
            }
        } else if (ACTION_OPEN_CHAT.equals(act)) {
            String target = in.getStringExtra("url");
            if (target != null && isOwnHost(target)) {
                triedFallback = false;
                web.loadUrl(target);
                return true;
            }
        } else if (ACTION_NEW_CHAT.equals(act)) {
            triedFallback = false;
            web.loadUrl(base);
            return true;
        }
        return false;
    }

    private void injectFile(Uri u) {
        try (InputStream is = getContentResolver().openInputStream(u)) {
            if (is == null) return;
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n, total = 0;
            while ((n = is.read(buf)) > 0) {
                total += n;
                if (total > MAX_SHARE_BYTES) {
                    Toast.makeText(this, "Shared file is too large (3 MB max)", Toast.LENGTH_LONG).show();
                    return;
                }
                bo.write(buf, 0, n);
            }
            String b64 = Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
            String mime = getContentResolver().getType(u);
            if (mime == null) mime = "application/octet-stream";
            String js = "(function(){try{var b=atob('" + b64 + "');var a=new Uint8Array(b.length);for(var i=0;i<b.length;i++)a[i]=b.charCodeAt(i);"
                + "var f=new File([a]," + JSONObject.quote(displayName(u)) + ",{type:" + JSONObject.quote(mime) + "});"
                + "var dt=new DataTransfer();dt.items.add(f);var inp=document.querySelector('input[type=file]');"
                + "if(inp){inp.files=dt.files;inp.dispatchEvent(new Event('change',{bubbles:true}));}}catch(e){}})();";
            web.evaluateJavascript(js, null);
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't read the shared file", Toast.LENGTH_LONG).show();
        }
    }

    private String displayName(Uri u) {
        try (Cursor c = getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        } catch (Exception ignored) {}
        return "shared-file";
    }

    private String hostOf(String url) {
        try { return Uri.parse(url).getHost(); } catch (Exception e) { return null; }
    }

    private String errorHtml() {
        return "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'><style>"
            + "body{font-family:sans-serif;padding:32px;text-align:center;background:#fff;color:#111}"
            + "@media(prefers-color-scheme:dark){body{background:#111;color:#eee}}"
            + "a{display:inline-block;margin-top:24px;padding:14px 28px;background:#4F46E5;color:#fff;border-radius:12px;text-decoration:none;font-weight:bold}"
            + "p{opacity:.7}</style></head><body><h2>Can't reach your server</h2>"
            + "<p>Check your connection or Wi-Fi, then try again.</p>"
            + "<a href='owui://retry'>Retry</a>"
            + "<p style='margin-top:32px;font-size:13px'>Press Back twice to open settings.</p></body></html>";
    }

    // ---- settings ----
    private void showMenu() {
        String fb = prefs.getString("fallback", "");
        boolean lock = prefs.getBoolean("lock", false);
        boolean keep = prefs.getBoolean("keepOn", false);
        String[] items = {
            "Server address",
            "Fallback address" + (fb.isEmpty() ? " (not set)" : ": " + fb),
            "Require screen lock on open: " + (lock ? "ON" : "OFF"),
            "Keep screen on: " + (keep ? "ON" : "OFF"),
            "Reload",
            "Open Community (plugins & tools)",
            "Servers (" + profiles().length() + " saved)",
            "Call mode (hands-free voice): " + (callMode ? "ON" : "OFF"),
            "Check for updates"
        };
        new AlertDialog.Builder(this).setTitle("Open WebUI").setItems(items, (d, w) -> {
            switch (w) {
                case 0: askUrl("url", "Open WebUI server address", false); break;
                case 1: askUrl("fallback", "Fallback address (used if the main one fails)", false); break;
                case 2: toggleLock(!lock); break;
                case 3:
                    prefs.edit().putBoolean("keepOn", !keep).apply();
                    applyKeepScreenOn();
                    break;
                case 4:
                    triedFallback = false;
                    String u = prefs.getString("url", null);
                    if (u != null) web.loadUrl(u);
                    break;
                case 5: openCommunity(); break;
                case 6: showServers(); break;
                case 7: toggleCallMode(); break;
                case 8: checkForUpdates(true); break;
            }
        }).show();
    }

    private void toggleLock(boolean on) {
        if (on) {
            KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            if (!km.isDeviceSecure()) {
                Toast.makeText(this, "Set up a PIN, pattern or biometric in Android settings first", Toast.LENGTH_LONG).show();
                return;
            }
        }
        prefs.edit().putBoolean("lock", on).apply();
    }

    private void applyKeepScreenOn() {
        if (prefs.getBoolean("keepOn", false) || callMode) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void askUrl(String key, String title, boolean required) {
        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        et.setHint("https://chat.example.com");
        et.setText(prefs.getString(key, ""));
        new AlertDialog.Builder(this).setTitle(title).setView(et).setCancelable(!required)
            .setPositiveButton("Save", (d, w) -> {
                String u = et.getText().toString().trim();
                if (u.isEmpty()) {
                    if (required) askUrl(key, title, true);
                    else prefs.edit().remove(key).apply();
                    return;
                }
                if (!u.matches("^https?://.*")) u = "https://" + u;
                prefs.edit().putString(key, u).apply();
                if (key.equals("url")) { triedFallback = false; web.loadUrl(u); }
            }).show();
    }

    // ---- own host check ----
    private boolean isOwnHost(String u) {
        String h = hostOf(u);
        return h != null && (h.equals(hostOf(prefs.getString("url", ""))) || h.equals(hostOf(prefs.getString("fallback", ""))));
    }

    // ---- file chooser with camera ----
    private Intent buildChooser(WebChromeClient.FileChooserParams p) {
        Intent chooser = Intent.createChooser(p.createIntent(), "Choose file");
        captureUri = null;
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            try {
                File dir = new File(getCacheDir(), "photos");
                dir.mkdirs();
                File f = File.createTempFile("photo_", ".jpg", dir);
                captureUri = FileProvider.getUriForFile(this, AUTH, f);
                Intent cam = new Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, captureUri)
                    .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{cam});
            } catch (Exception e) { captureUri = null; }
        }
        return chooser;
    }

    // ---- downloads ----
    private void handleDownload(WebView source, String url, String ua, String cd, String mime) {
        String type = (mime == null || mime.isEmpty()) ? "application/octet-stream" : mime;
        String name = URLUtil.guessFileName(url, cd, type);
        if (url.startsWith("blob:")) {
            String js = "(function(){fetch('" + url.replace("'", "%27") + "').then(function(r){return r.blob();}).then(function(b){var fr=new FileReader();"
                + "fr.onload=function(){AndroidDl.save(" + JSONObject.quote(name) + "," + JSONObject.quote(type) + ",String(fr.result).split(',')[1]);};fr.readAsDataURL(b);});})();";
            source.evaluateJavascript(js, null);
            return;
        }
        if (!url.startsWith("http")) {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception ignored) {}
            return;
        }
        try {
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            String ck = CookieManager.getInstance().getCookie(url);
            if (ck != null) r.addRequestHeader("Cookie", ck);
            if (ua != null) r.addRequestHeader("User-Agent", ua);
            r.setMimeType(type).setTitle(name)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(r);
            Toast.makeText(this, "Downloading " + name, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception ignored) {}
        }
    }

    private void saveBase64(String name, String mime, String b64) {
        try {
            byte[] data = Base64.decode(b64, Base64.DEFAULT);
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                v.put(MediaStore.Downloads.MIME_TYPE, mime);
                v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                try (OutputStream os = getContentResolver().openOutputStream(u)) { os.write(data); }
            } else {
                File d = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                try (FileOutputStream os = new FileOutputStream(new File(d, name))) { os.write(data); }
            }
            runOnUiThread(() -> Toast.makeText(this, "Saved to Downloads: " + name, Toast.LENGTH_LONG).show());
        } catch (Exception e) {
            runOnUiThread(() -> Toast.makeText(this, "Couldn't save the file", Toast.LENGTH_LONG).show());
        }
    }

    // ---- in-app updates ----
    private boolean isNewer(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = 0, q = 0;
            try { if (i < x.length) p = Integer.parseInt(x[i].trim()); } catch (Exception ignored) {}
            try { if (i < y.length) q = Integer.parseInt(y[i].trim()); } catch (Exception ignored) {}
            if (p != q) return p > q;
        }
        return false;
    }

    private void checkForUpdates(boolean manual) {
        if (!manual && System.currentTimeMillis() - prefs.getLong("lastUpdateCheck", 0) < 12L * 3600 * 1000) return;
        prefs.edit().putLong("lastUpdateCheck", System.currentTimeMillis()).apply();
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(RELEASES_API).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.setRequestProperty("Accept", "application/vnd.github+json");
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                try (InputStream is = c.getInputStream()) {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
                }
                JSONObject j = new JSONObject(bo.toString("UTF-8"));
                String tag = j.optString("tag_name").replaceFirst("^v", "");
                String apk = null;
                JSONArray assets = j.optJSONArray("assets");
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject a = assets.getJSONObject(i);
                        if (a.optString("name").endsWith(".apk")) apk = a.optString("browser_download_url");
                    }
                }
                String cur = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
                final String fApk = apk, fTag = tag, fCur = cur;
                if (apk != null && isNewer(tag, cur)) runOnUiThread(() -> showUpdate(fTag, fApk));
                else if (manual) runOnUiThread(() -> Toast.makeText(this, "You're up to date (v" + fCur + ")", Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                if (manual) runOnUiThread(() -> Toast.makeText(this, "Couldn't check for updates", Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void showUpdate(String tag, String apkUrl) {
        new AlertDialog.Builder(this).setTitle("Update available")
            .setMessage("Open WebUI v" + tag + " is available. Download and install it now?")
            .setPositiveButton("Download", (d, w) -> {
                try {
                    File f = new File(getExternalFilesDir(null), "update.apk");
                    if (f.exists()) f.delete();
                    DownloadManager.Request r = new DownloadManager.Request(Uri.parse(apkUrl))
                        .setTitle("Open WebUI v" + tag)
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setDestinationInExternalFilesDir(this, null, "update.apk");
                    updateDownloadId = ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(r);
                    Toast.makeText(this, "Downloading update\u2026", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(this, "Couldn't start the download", Toast.LENGTH_LONG).show();
                }
            })
            .setNegativeButton("Later", null).show();
    }

    private void installUpdate() {
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            pendingInstall = true;
            Toast.makeText(this, "Allow installs from this app, then come back", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            return;
        }
        try {
            Uri u = FileProvider.getUriForFile(this, AUTH, new File(getExternalFilesDir(null), "update.apk"));
            startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(u, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't open the update", Toast.LENGTH_LONG).show();
        }
    }

    // ---- server profiles ----
    private JSONArray profiles() {
        try { return new JSONArray(prefs.getString("profiles", "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    private void saveProfiles(JSONArray a) { prefs.edit().putString("profiles", a.toString()).apply(); }

    private void ensureProfile() {
        String cur = prefs.getString("url", "");
        if (cur.isEmpty()) return;
        JSONArray a = profiles();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && cur.equals(o.optString("url"))) return;
        }
        try {
            JSONObject o = new JSONObject();
            o.put("name", a.length() == 0 ? "Main" : "Server " + (a.length() + 1));
            o.put("url", cur);
            o.put("fallback", prefs.getString("fallback", ""));
            a.put(o);
            saveProfiles(a);
        } catch (Exception ignored) {}
    }

    private void switchTo(String url, String fallback) {
        SharedPreferences.Editor ed = prefs.edit().putString("url", url);
        if (fallback == null || fallback.isEmpty()) ed.remove("fallback"); else ed.putString("fallback", fallback);
        ed.apply();
        triedFallback = false;
        minimizeCommunity();
        web.loadUrl(url);
    }

    private void showServers() {
        ensureProfile();
        final JSONArray a = profiles();
        String cur = prefs.getString("url", "");
        ArrayList<String> labels = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) o = new JSONObject();
            labels.add((cur.equals(o.optString("url")) ? "\u2713 " : "") + o.optString("name") + " \u2014 " + o.optString("url"));
        }
        labels.add("+ Add server");
        labels.add("Remove current server from list");
        new AlertDialog.Builder(this).setTitle("Servers").setItems(labels.toArray(new String[0]), (d, w) -> {
            int n = a.length();
            if (w < n) {
                JSONObject o = a.optJSONObject(w);
                if (o != null) switchTo(o.optString("url"), o.optString("fallback"));
            } else if (w == n) addServerDialog();
            else removeCurrentServer();
        }).show();
    }

    private void addServerDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        final EditText name = new EditText(this);
        name.setHint("Name (e.g. Home)");
        final EditText addr = new EditText(this);
        addr.setHint("https://chat.example.com");
        addr.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        final EditText fb = new EditText(this);
        fb.setHint("Fallback address (optional)");
        fb.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        box.addView(name);
        box.addView(addr);
        box.addView(fb);
        new AlertDialog.Builder(this).setTitle("Add server").setView(box).setPositiveButton("Save", (d, w) -> {
            String u = addr.getText().toString().trim();
            if (u.isEmpty()) return;
            if (!u.matches("^https?://.*")) u = "https://" + u;
            String f = fb.getText().toString().trim();
            if (!f.isEmpty() && !f.matches("^https?://.*")) f = "https://" + f;
            String n = name.getText().toString().trim();
            try {
                JSONArray a = profiles();
                JSONObject o = new JSONObject();
                o.put("name", n.isEmpty() ? "Server " + (a.length() + 1) : n);
                o.put("url", u);
                o.put("fallback", f);
                a.put(o);
                saveProfiles(a);
            } catch (Exception ignored) {}
            switchTo(u, f);
        }).setNegativeButton("Cancel", null).show();
    }

    private void removeCurrentServer() {
        String cur = prefs.getString("url", "");
        JSONArray a = profiles();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && cur.equals(o.optString("url"))) { a.remove(i); break; }
        }
        saveProfiles(a);
        JSONObject first = a.length() > 0 ? a.optJSONObject(0) : null;
        if (first != null) switchTo(first.optString("url"), first.optString("fallback"));
        else Toast.makeText(this, "Removed from the list (still connected)", Toast.LENGTH_SHORT).show();
    }

    // ---- call mode ----
    private void toggleCallMode() {
        callMode = !callMode;
        applyCallMode();
    }

    private void applyCallMode() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (callMode) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            try { am.setMode(AudioManager.MODE_IN_COMMUNICATION); am.setSpeakerphoneOn(true); } catch (Exception ignored) {}
            KeepAliveService.mic = true;
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                try { startForegroundService(new Intent(this, KeepAliveService.class)); } catch (Exception ignored) {}
            }
            Toast.makeText(this, "Call mode on: mic stays live in the background", Toast.LENGTH_LONG).show();
        } else {
            applyKeepScreenOn();
            try { am.setMode(AudioManager.MODE_NORMAL); am.setSpeakerphoneOn(false); } catch (Exception ignored) {}
            KeepAliveService.mic = false;
            if (inForeground) stopService(new Intent(this, KeepAliveService.class));
        }
    }

    // ---- community hub ----
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private TextView barButton(String text, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(16);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), 0, dp(12), 0);
        return t;
    }

    private void buildCommunity(FrameLayout root) {
        int bg = getColor(R.color.bg), fg = getColor(R.color.fg);

        // floating button (drag up/down to move, tap to open or minimize)
        fab = new TextView(this);
        fab.setText("\uD83E\uDDE9");
        fab.setTextSize(22);
        fab.setGravity(Gravity.CENTER);
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.OVAL);
        gd.setColor(Color.parseColor("#4F46E5"));
        fab.setBackground(gd);
        fab.setElevation(dp(6));
        fab.setAlpha(0.92f);
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(dp(52), dp(52));
        flp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        flp.rightMargin = dp(8);
        root.addView(fab, flp);
        final float[] drag = new float[3];
        fab.setOnTouchListener((v, e) -> {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    drag[0] = e.getRawY(); drag[1] = v.getTranslationY(); drag[2] = 0;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dy = e.getRawY() - drag[0];
                    if (Math.abs(dy) > dp(8)) drag[2] = 1;
                    if (drag[2] == 1) v.setTranslationY(drag[1] + dy);
                    return true;
                case MotionEvent.ACTION_UP:
                    if (drag[2] == 0) {
                        if (panel.getVisibility() == View.VISIBLE) minimizeCommunity(); else openCommunity();
                    }
                    return true;
            }
            return false;
        });

        // full-screen panel with its own header
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(bg);
        panel.setClickable(true);
        panel.setVisibility(View.GONE);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = barButton("\u2190 Chat", fg);
        TextView home = barButton("\u2302", fg);
        TextView title = new TextView(this);
        title.setText("Open WebUI Community");
        title.setTextColor(fg);
        title.setTextSize(15);
        title.setGravity(Gravity.CENTER);
        TextView close = barButton("\u2715", fg);
        top.addView(back, new LinearLayout.LayoutParams(-2, -1));
        top.addView(title, new LinearLayout.LayoutParams(0, -1, 1f));
        top.addView(home, new LinearLayout.LayoutParams(-2, -1));
        top.addView(close, new LinearLayout.LayoutParams(-2, -1));
        back.setOnClickListener(v -> minimizeCommunity());
        home.setOnClickListener(v -> community.loadUrl(COMMUNITY_URL));
        close.setOnClickListener(v -> closeCommunity());
        panel.addView(top, new LinearLayout.LayoutParams(-1, dp(48)));

        community = new WebView(this);
        WebSettings cs = community.getSettings();
        cs.setJavaScriptEnabled(true);
        cs.setDomStorageEnabled(true);
        cs.setSupportMultipleWindows(true);
        cs.setJavaScriptCanOpenWindowsAutomatically(true);
        cs.setAllowFileAccess(false);
        CookieManager.getInstance().setAcceptThirdPartyCookies(community, true);
        community.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                String sch = u.getScheme();
                if (sch == null) return true;
                if (!sch.equals("http") && !sch.equals("https")) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) {}
                    return true;
                }
                String host = u.getHost();
                if (host != null && (host.equals(hostOf(prefs.getString("url", ""))) || host.equals(hostOf(prefs.getString("fallback", ""))))) {
                    // e.g. "Import to Open WebUI": jump back to your own server inside the app
                    minimizeCommunity();
                    triedFallback = false;
                    web.loadUrl(u.toString());
                    return true;
                }
                return false;
            }
        });
        community.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                WebView tmp = new WebView(MainActivity.this);
                tmp.setWebViewClient(new WebViewClient() {
                    @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                        community.loadUrl(r.getUrl().toString());
                        return true;
                    }
                });
                ((WebView.WebViewTransport) resultMsg.obj).setWebView(tmp);
                resultMsg.sendToTarget();
                return true;
            }
        });
        community.setDownloadListener((url, ua, cd, mime, len) -> handleDownload(community, url, ua, cd, mime));
        panel.addView(community, new LinearLayout.LayoutParams(-1, 0, 1f));
        root.addView(panel, new FrameLayout.LayoutParams(-1, -1));
    }

    private void openCommunity() {
        if (!communityActive) community.loadUrl(COMMUNITY_URL);
        communityActive = true;
        panel.setVisibility(View.VISIBLE);
        updateFab();
    }

    private void minimizeCommunity() {
        if (panel == null) return;
        panel.setVisibility(View.GONE);
        updateFab();
    }

    private void closeCommunity() {
        panel.setVisibility(View.GONE);
        communityActive = false;
        community.loadUrl("about:blank");
        community.clearHistory();
        updateFab();
    }

    private void updateFab() {
        ((GradientDrawable) fab.getBackground()).setColor(Color.parseColor(communityActive ? "#16A34A" : "#4F46E5"));
    }

    // ---- lock ----
    private void requireAuth() {
        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (!km.isDeviceSecure()) { unlocked = true; lockCover.setVisibility(View.GONE); return; }
        authPending = true;
        if (Build.VERSION.SDK_INT >= 30) {
            BiometricPrompt bp = new BiometricPrompt.Builder(this)
                .setTitle("Open WebUI").setSubtitle("Unlock to continue")
                .setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
                    | android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build();
            bp.authenticate(new CancellationSignal(), getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
                @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult r) {
                    authPending = false; unlocked = true; lockCover.setVisibility(View.GONE);
                }
                @Override public void onAuthenticationError(int code, CharSequence msg) {
                    authPending = false;
                    if (code == 5) return; // canceled by the system (e.g. app left the screen); asks again on resume
                    finish();
                }
            });
        } else {
            Intent i = km.createConfirmDeviceCredentialIntent("Open WebUI", "Unlock to continue");
            if (i == null) { authPending = false; unlocked = true; lockCover.setVisibility(View.GONE); return; }
            startActivityForResult(i, REQ_AUTH);
        }
    }

    // ---- permissions / notifications ----
    private void askStartupPermissions() {
        if (prefs.getBoolean("askedPerms", false)) return;
        ArrayList<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.RECORD_AUDIO);
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.CAMERA);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.POST_NOTIFICATIONS);
        prefs.edit().putBoolean("askedPerms", true).apply();
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), REQ_START);
    }

    private void notifyDone(String preview, String url) {
        if (inForeground) return;
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("done", "Response finished", NotificationManager.IMPORTANCE_DEFAULT));
        int id = notifId++;
        Intent open = new Intent(this, MainActivity.class).setAction(ACTION_OPEN_CHAT).putExtra("url", url)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        PendingIntent pi = PendingIntent.getActivity(this, id, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String text = (preview == null || preview.trim().isEmpty()) ? "Your response is ready" : preview.trim();
        nm.notify(id, new android.app.Notification.Builder(this, "done")
            .setSmallIcon(android.R.drawable.stat_notify_chat).setContentTitle("Open WebUI")
            .setContentText(text).setStyle(new android.app.Notification.BigTextStyle().bigText(text))
            .setContentIntent(pi).setAutoCancel(true).build());
    }

    @Override protected void onPause() {
        super.onPause();
        inForeground = false;
        pausedAt = System.currentTimeMillis();
        KeepAliveService.mic = callMode;
        try { startForegroundService(new Intent(this, KeepAliveService.class)); } catch (Exception ignored) {}
    }

    @Override protected void onResume() {
        super.onResume();
        inForeground = true;
        if (!callMode) stopService(new Intent(this, KeepAliveService.class));
        if (pendingInstall && Build.VERSION.SDK_INT >= 26 && getPackageManager().canRequestPackageInstalls()) {
            pendingInstall = false;
            installUpdate();
        }
        boolean lock = prefs.getBoolean("lock", false);
        if (lock && unlocked && System.currentTimeMillis() - pausedAt > 60000) {
            unlocked = false;
            lockCover.setVisibility(View.VISIBLE);
            requireAuth();
        } else if (lock && !unlocked && !authPending) {
            requireAuth();
        }
    }

    @Override protected void onDestroy() {
        try { unregisterReceiver(dlReceiver); } catch (Exception ignored) {}
        if (callMode) { callMode = false; applyCallMode(); }
        super.onDestroy();
    }

    private long lastBack;
    @Override public void onBackPressed() {
        if (panel != null && panel.getVisibility() == View.VISIBLE) {
            if (community.canGoBack()) community.goBack(); else minimizeCommunity();
            return;
        }
        if (web.canGoBack()) { web.goBack(); return; }
        long now = System.currentTimeMillis();
        if (now - lastBack < 1500) showMenu();
        else { lastBack = now; Toast.makeText(this, "Press back again for settings", Toast.LENGTH_SHORT).show(); }
    }

    @Override protected void onActivityResult(int rq, int rs, Intent d) {
        if (rq == REQ_FILE && fileCb != null) {
            Uri[] res = null;
            if (rs == RESULT_OK) {
                if (d != null && (d.getData() != null || d.getClipData() != null)) res = WebChromeClient.FileChooserParams.parseResult(rs, d);
                else if (captureUri != null) res = new Uri[]{captureUri};
            }
            captureUri = null;
            fileCb.onReceiveValue(res);
            fileCb = null;
        } else if (rq == REQ_AUTH) {
            authPending = false;
            if (rs == RESULT_OK) { unlocked = true; lockCover.setVisibility(View.GONE); }
            else finish();
        } else super.onActivityResult(rq, rs, d);
    }

    @Override public void onRequestPermissionsResult(int rq, String[] p, int[] r) {
        if (rq == REQ_PERM && pendingPerm != null) {
            if (r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) pendingPerm.grant(pendingPerm.getResources());
            else pendingPerm.deny();
            pendingPerm = null;
        }
    }
}
