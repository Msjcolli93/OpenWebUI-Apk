package com.openwebui.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
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
import android.widget.EditText;
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
            @JavascriptInterface public void responseDone() { runOnUiThread(() -> notifyDone()); }
        }, "AndroidNotify");
        CookieManager.getInstance().setAcceptCookie(true);
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
                v.evaluateJavascript("(function(){if(window.__owuiWatch)return;window.__owuiWatch=1;var was=false;setInterval(function(){var on=!!document.getElementById('stop-response-button');if(was&&!on&&window.AndroidNotify)AndroidNotify.responseDone();was=on;},1000);})();", null);
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
                try { startActivityForResult(p.createIntent(), REQ_FILE); }
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
        web.setDownloadListener((url, ua, cd, mime, len) -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))));

        askStartupPermissions();

        if (prefs.getBoolean("lock", false)) {
            lockCover.setVisibility(View.VISIBLE);
            requireAuth();
        } else {
            unlocked = true;
        }

        String url = prefs.getString("url", null);
        if (url == null) askUrl("url", "Open WebUI server address", true);
        else if (!handleIntent(getIntent())) web.loadUrl(url);
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
            "Reload"
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
        if (prefs.getBoolean("keepOn", false)) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
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

    // ---- lock ----
    private void requireAuth() {
        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        Intent i = km.isDeviceSecure() ? km.createConfirmDeviceCredentialIntent("Open WebUI", "Unlock to continue") : null;
        if (i == null) { unlocked = true; lockCover.setVisibility(View.GONE); return; }
        startActivityForResult(i, REQ_AUTH);
    }

    // ---- permissions / notifications ----
    private void askStartupPermissions() {
        if (prefs.getBoolean("askedPerms", false)) return;
        ArrayList<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.POST_NOTIFICATIONS);
        prefs.edit().putBoolean("askedPerms", true).apply();
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), REQ_START);
    }

    private void notifyDone() {
        if (inForeground) return;
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("done", "Response finished", NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent pi = PendingIntent.getActivity(this, 0,
            new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT), PendingIntent.FLAG_IMMUTABLE);
        nm.notify(notifId++, new android.app.Notification.Builder(this, "done")
            .setSmallIcon(android.R.drawable.stat_notify_chat).setContentTitle("Open WebUI")
            .setContentText("Your response is ready").setContentIntent(pi).setAutoCancel(true).build());
    }

    @Override protected void onPause() {
        super.onPause();
        inForeground = false;
        pausedAt = System.currentTimeMillis();
        try { startForegroundService(new Intent(this, KeepAliveService.class)); } catch (Exception ignored) {}
    }

    @Override protected void onResume() {
        super.onResume();
        inForeground = true;
        stopService(new Intent(this, KeepAliveService.class));
        if (prefs.getBoolean("lock", false) && unlocked && System.currentTimeMillis() - pausedAt > 60000) {
            unlocked = false;
            lockCover.setVisibility(View.VISIBLE);
            requireAuth();
        }
    }

    private long lastBack;
    @Override public void onBackPressed() {
        if (web.canGoBack()) { web.goBack(); return; }
        long now = System.currentTimeMillis();
        if (now - lastBack < 1500) showMenu();
        else { lastBack = now; Toast.makeText(this, "Press back again for settings", Toast.LENGTH_SHORT).show(); }
    }

    @Override protected void onActivityResult(int rq, int rs, Intent d) {
        if (rq == REQ_FILE && fileCb != null) {
            fileCb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(rs, d));
            fileCb = null;
        } else if (rq == REQ_AUTH) {
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
