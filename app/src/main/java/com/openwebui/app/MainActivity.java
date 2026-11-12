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
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.media.RingtoneManager;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import org.json.JSONTokener;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;

public class MainActivity extends Activity {
    public static final String ACTION_NEW_CHAT = "com.openwebui.app.NEW_CHAT";
    private static final int REQ_FILE = 1, REQ_PERM = 2, REQ_START = 3, REQ_AUTH = 4, REQ_SPEECH = 5, REQ_IMPORT = 6;
    public static final String ACTION_REPLY = "com.openwebui.app.REPLY";
    private final Handler ui = new Handler(Looper.getMainLooper());
    private View dot;
    private TextView micFab, speakFab;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private String pendingReply;
    private int failCount = 0;
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
        buildTools(root);
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
        s.setTextZoom(pInt("zoom", 100));
        web.addJavascriptInterface(new Object() {
            @JavascriptInterface public void responseDone(String preview, String url) { runOnUiThread(() -> onResponseDone(preview, url)); }
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
                String sch = u.getScheme();
                if (prefs.getBoolean("linksIn", false) && ("http".equals(sch) || "https".equals(sch))) { openInPanel(u.toString()); return true; }
                startActivity(new Intent(Intent.ACTION_VIEW, u));
                return true;
            }
            @Override public void onPageStarted(WebView v, String u, Bitmap f) { bar.setVisibility(View.VISIBLE); }
            @Override public void onPageFinished(WebView v, String u) {
                bar.setVisibility(View.GONE);
                v.evaluateJavascript("(function(){if(window.__owuiWatch)return;window.__owuiWatch=1;var was=false;setInterval(function(){var on=!!document.getElementById('stop-response-button');if(was&&!on&&window.AndroidNotify){var m=" + MSGS + ";var t=m.length?m[m.length-1].innerText:'';AndroidNotify.responseDone((t||'').trim().substring(0,160),location.href);}was=on;},1000);})();", null);
                String th = pStr("theme", "auto");
                if (!th.equals("auto")) v.evaluateJavascript(themeScript(th), null);
                if (pendingReply != null && u != null && !u.startsWith("about:")) {
                    final String rep = pendingReply;
                    pendingReply = null;
                    v.postDelayed(() -> sendReply(rep), 2500);
                }
                if (pendingShareUri != null && u != null && !u.startsWith("about:")) {
                    final Uri share = pendingShareUri;
                    pendingShareUri = null;
                    v.postDelayed(() -> injectFile(share, 0), 2500);
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

        if (pBool("lock", false)) {
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
        if (Intent.ACTION_SEND.equals(act) || ACTION_NEW_CHAT.equals(act) || ACTION_OPEN_CHAT.equals(act) || ACTION_REPLY.equals(act)) minimizeCommunity();
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
        } else if (ACTION_REPLY.equals(act)) {
            Bundle ri = android.app.RemoteInput.getResultsFromIntent(in);
            CharSequence msg = ri == null ? null : ri.getCharSequence("reply");
            int nid = in.getIntExtra("nid", -1);
            if (nid >= 0) getSystemService(NotificationManager.class).cancel(nid);
            String target = in.getStringExtra("url");
            if (msg != null && msg.length() > 0 && target != null && isOwnHost(target)) {
                pendingReply = msg.toString();
                triedFallback = false;
                web.loadUrl(target);
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

    private void injectFile(Uri u, int attempt) {
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
                + "if(inp){inp.files=dt.files;inp.dispatchEvent(new Event('change',{bubbles:true}));return 'ok';}return 'none';}catch(e){return 'err';}})();";
            final Uri again = u;
            web.evaluateJavascript(js, r -> {
                if (r != null && r.contains("none") && attempt < 6) web.postDelayed(() -> injectFile(again, attempt + 1), 1500);
            });
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
            + "a{display:inline-block;margin-top:24px;padding:14px 28px;background:#E11D2E;color:#fff;border-radius:12px;text-decoration:none;font-weight:bold}"
            + "p{opacity:.7}</style></head><body><h2>Can't reach your server</h2>"
            + "<p>Check your connection or Wi-Fi, then try again.</p>"
            + "<a href='owui://retry'>Retry</a>"
            + "<p style='margin-top:32px;font-size:13px'>Press Back twice to open settings.</p></body></html>";
    }

    // ---- settings ----
    private void showMenu() {
        String fb = prefs.getString("fallback", "");
        String[] items = {
            "Server address",
            "Fallback address" + (fb.isEmpty() ? " (not set)" : ": " + fb),
            "Servers (" + profiles().length() + " saved)",
            "Chat tools \u203A",
            "Appearance & behavior \u203A",
            "Open Community (plugins & tools)",
            "Call mode (hands-free voice): " + (callMode ? "ON" : "OFF"),
            "Reload",
            "Check for updates"
        };
        new AlertDialog.Builder(this).setTitle("Open WebUI").setItems(items, (d, w) -> {
            switch (w) {
                case 0: askUrl("url", "Open WebUI server address", false); break;
                case 1: askUrl("fallback", "Fallback address (used if the main one fails)", false); break;
                case 2: showServers(); break;
                case 3: showChatTools(); break;
                case 4: showAppearance(); break;
                case 5: openCommunity(); break;
                case 6: toggleCallMode(); break;
                case 7: {
                    triedFallback = false;
                    String u = prefs.getString("url", null);
                    if (u != null) web.loadUrl(u);
                    break;
                }
                case 8: checkForUpdates(true); break;
            }
        }).show();
    }

    private String onOff(boolean b) { return b ? "ON" : "OFF"; }

    private void showChatTools() {
        String[] items = {
            "Share this chat",
            "Pin this chat to the home screen",
            "Read the last reply aloud",
            "Dictate a message (voice to text)",
            "Save this chat offline now",
            "Offline chats (" + ChatArchive.list(this).size() + ")"
        };
        new AlertDialog.Builder(this).setTitle("Chat tools").setItems(items, (d, w) -> {
            switch (w) {
                case 0: shareChat(); break;
                case 1: pinChat(); break;
                case 2: readLast(); break;
                case 3: dictate(); break;
                case 4: archiveCurrent(true); break;
                case 5: showArchive(); break;
            }
        }).setNegativeButton("Back", (d, w) -> showMenu()).show();
    }

    private String themeName() {
        String t = pStr("theme", "auto");
        return t.equals("dark") ? "Dark" : t.equals("light") ? "Light" : "Automatic";
    }

    private void showAppearance() {
        String[] items = {
            "Text size: " + pInt("zoom", 100) + "% (this server)",
            "Theme: " + themeName() + " (this server)",
            "Links open in: " + (prefs.getBoolean("linksIn", false) ? "In-app panel" : "Your browser"),
            "Vibrate when a reply finishes: " + onOff(prefs.getBoolean("haptic", true)),
            "Sound when a reply finishes: " + onOff(prefs.getBoolean("sound", false)),
            "Read replies aloud automatically: " + onOff(prefs.getBoolean("autoRead", false)),
            "Floating mic & speaker buttons: " + onOff(prefs.getBoolean("tools", true)),
            "Server status dot: " + onOff(prefs.getBoolean("health", true)),
            "Floating bubble when app is in background: " + onOff(prefs.getBoolean("bubble", false)),
            "Require screen lock (this server): " + onOff(pBool("lock", false)),
            "Keep screen on (this server): " + onOff(pBool("keepOn", false))
        };
        new AlertDialog.Builder(this).setTitle("Appearance & behavior").setItems(items, (d, w) -> {
            switch (w) {
                case 0: textSizeDialog(); return;
                case 1: themeDialog(); return;
                case 2: prefs.edit().putBoolean("linksIn", !prefs.getBoolean("linksIn", false)).apply(); break;
                case 3: prefs.edit().putBoolean("haptic", !prefs.getBoolean("haptic", true)).apply(); break;
                case 4: prefs.edit().putBoolean("sound", !prefs.getBoolean("sound", false)).apply(); break;
                case 5: prefs.edit().putBoolean("autoRead", !prefs.getBoolean("autoRead", false)).apply(); break;
                case 6: prefs.edit().putBoolean("tools", !prefs.getBoolean("tools", true)).apply(); applyToolVisibility(); break;
                case 7: prefs.edit().putBoolean("health", !prefs.getBoolean("health", true)).apply(); applyToolVisibility(); checkHealth(); break;
                case 8:
                    if (!prefs.getBoolean("bubble", false)) {
                        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
                            Toast.makeText(this, "Allow \"Display over other apps\" for Open WebUI, then turn this on again", Toast.LENGTH_LONG).show();
                            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
                            return;
                        }
                        prefs.edit().putBoolean("bubble", true).apply();
                    } else prefs.edit().putBoolean("bubble", false).apply();
                    break;
                case 9: toggleLock(!pBool("lock", false)); break;
                case 10: putBool("keepOn", !pBool("keepOn", false)); applyKeepScreenOn(); break;
            }
            showAppearance();
        }).setNegativeButton("Back", (d, w) -> showMenu()).show();
    }

    private void textSizeDialog() {
        final int cur = pInt("zoom", 100);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(16), dp(24), dp(8));
        final TextView lab = new TextView(this);
        lab.setText(cur + "%");
        lab.setTextSize(18);
        lab.setGravity(Gravity.CENTER);
        final SeekBar sb = new SeekBar(this);
        sb.setMax(130);
        sb.setProgress(cur - 70);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int p, boolean user) {
                lab.setText((p + 70) + "%");
                web.getSettings().setTextZoom(p + 70);
            }
            @Override public void onStartTrackingTouch(SeekBar b) {}
            @Override public void onStopTrackingTouch(SeekBar b) {}
        });
        box.addView(lab);
        box.addView(sb);
        new AlertDialog.Builder(this).setTitle("Text size").setView(box)
            .setPositiveButton("Save", (d, w) -> { putInt("zoom", sb.getProgress() + 70); showAppearance(); })
            .setNegativeButton("Cancel", (d, w) -> { web.getSettings().setTextZoom(cur); showAppearance(); })
            .setOnCancelListener(d -> web.getSettings().setTextZoom(cur)).show();
    }

    private String themeScript(String t) {
        if (t.equals("auto")) return "try{localStorage.setItem('theme','system');}catch(e){}";
        boolean dark = t.equals("dark");
        return "(function(){try{localStorage.setItem('theme','" + t + "');var r=document.documentElement;r.classList.remove('" + (dark ? "light" : "dark") + "');r.classList.add('" + t + "');}catch(e){}})();";
    }

    private void themeDialog() {
        final String[] keys = {"auto", "light", "dark"};
        new AlertDialog.Builder(this).setTitle("Theme (this server)")
            .setItems(new String[]{"Automatic (follow Open WebUI)", "Light", "Dark"}, (d, w) -> {
                putStr("theme", keys[w]);
                web.evaluateJavascript(themeScript(keys[w]), null);
                if (keys[w].equals("auto")) web.reload();
                showAppearance();
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
        putBool("lock", on);
    }

    private void applyKeepScreenOn() {
        if (pBool("keepOn", false) || callMode) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
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
        if (manual) Toast.makeText(this, "Checking for updates\u2026", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String tag = null, apk = null;
            Exception err = null;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(RELEASES_API).openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(10000);
                c.setRequestProperty("Accept", "application/vnd.github+json");
                c.setRequestProperty("User-Agent", "OpenWebUI-Android");
                if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                try (InputStream is = c.getInputStream()) {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
                }
                JSONObject j = new JSONObject(bo.toString("UTF-8"));
                tag = j.optString("tag_name").replaceFirst("^v", "");
                JSONArray assets = j.optJSONArray("assets");
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject a = assets.getJSONObject(i);
                        if (a.optString("name").endsWith(".apk")) apk = a.optString("browser_download_url");
                    }
                }
            } catch (Exception e) {
                err = e;
            }
            if (tag == null || tag.isEmpty() || apk == null) {
                // fallback: read the redirect of the public "latest release" page (no API, no rate limit)
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL("https://github.com/Msjcolli93/OpenWebUI-Apk/releases/latest").openConnection();
                    c.setInstanceFollowRedirects(false);
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(10000);
                    c.setRequestProperty("User-Agent", "OpenWebUI-Android");
                    String loc = c.getHeaderField("Location");
                    if (loc != null && loc.contains("/tag/")) {
                        tag = loc.substring(loc.lastIndexOf("/tag/") + 5).replaceFirst("^v", "");
                        apk = "https://github.com/Msjcolli93/OpenWebUI-Apk/releases/download/v" + tag + "/OpenWebUI-v" + tag + ".apk";
                        err = null;
                    } else if (err == null) {
                        err = new Exception("no release found");
                    }
                } catch (Exception e) {
                    err = e;
                }
            }
            String cur;
            try { cur = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { cur = "0"; }
            final String fTag = tag, fApk = apk, fCur = cur;
            final Exception fErr = err;
            if (fTag != null && fApk != null && isNewer(fTag, fCur)) {
                runOnUiThread(() -> showUpdate(fTag, fApk));
            } else if (manual) {
                runOnUiThread(() -> Toast.makeText(this, fTag == null
                    ? "Couldn't check for updates: " + (fErr == null ? "unknown error" : fErr.getClass().getSimpleName() + " " + fErr.getMessage())
                    : "You're up to date (v" + fCur + ")", Toast.LENGTH_LONG).show());
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
        web.getSettings().setTextZoom(pInt("zoom", 100));
        applyKeepScreenOn();
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
        labels.add("Export servers to a file");
        labels.add("Import servers from a file");
        new AlertDialog.Builder(this).setTitle("Servers").setItems(labels.toArray(new String[0]), (d, w) -> {
            int n = a.length();
            if (w < n) {
                JSONObject o = a.optJSONObject(w);
                if (o != null) switchTo(o.optString("url"), o.optString("fallback"));
            } else if (w == n) addServerDialog();
            else if (w == n + 1) removeCurrentServer();
            else if (w == n + 2) exportServers();
            else importServersPicker();
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
        gd.setColor(Color.parseColor("#E11D2E"));
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
        ((GradientDrawable) fab.getBackground()).setColor(Color.parseColor(communityActive ? "#16A34A" : "#E11D2E"));
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
        Intent reply = new Intent(this, MainActivity.class).setAction(ACTION_REPLY).putExtra("url", url).putExtra("nid", id)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        int mutable = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
        PendingIntent rp = PendingIntent.getActivity(this, 100000 + id, reply, PendingIntent.FLAG_UPDATE_CURRENT | mutable);
        android.app.RemoteInput ri = new android.app.RemoteInput.Builder("reply").setLabel("Reply").build();
        android.app.Notification.Action act = new android.app.Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_send), "Reply", rp).addRemoteInput(ri).build();
        String text = (preview == null || preview.trim().isEmpty()) ? "Your response is ready" : preview.trim();
        nm.notify(id, new android.app.Notification.Builder(this, "done")
            .setSmallIcon(android.R.drawable.stat_notify_chat).setContentTitle("Open WebUI")
            .setContentText(text).setStyle(new android.app.Notification.BigTextStyle().bigText(text))
            .addAction(act).setContentIntent(pi).setAutoCancel(true).build());
    }

    @Override protected void onPause() {
        super.onPause();
        inForeground = false;
        ui.removeCallbacks(healthTick);
        KeepAliveService.appVisible(false);
        pausedAt = System.currentTimeMillis();
        KeepAliveService.mic = callMode;
        try { startForegroundService(new Intent(this, KeepAliveService.class)); } catch (Exception ignored) {}
    }

    @Override protected void onResume() {
        super.onResume();
        inForeground = true;
        KeepAliveService.appVisible(true);
        ui.removeCallbacks(healthTick);
        ui.postDelayed(healthTick, 1000);
        if (!callMode) stopService(new Intent(this, KeepAliveService.class));
        if (pendingInstall && Build.VERSION.SDK_INT >= 26 && getPackageManager().canRequestPackageInstalls()) {
            pendingInstall = false;
            installUpdate();
        }
        boolean lock = pBool("lock", false);
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
        if (tts != null) { try { tts.stop(); tts.shutdown(); } catch (Exception ignored) {} }
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
        } else if (rq == REQ_SPEECH) {
            if (rs == RESULT_OK && d != null) {
                ArrayList<String> r = d.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                if (r != null && !r.isEmpty()) insertIntoChat(r.get(0), null);
            }
        } else if (rq == REQ_IMPORT) {
            if (rs == RESULT_OK && d != null && d.getData() != null) importServersFrom(d.getData());
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

    // ===== v1.5 additions =====
    private static final String MSGS = "Array.prototype.filter.call(document.querySelectorAll('[id^=\"message-\"]'),function(x){return /^message-[0-9a-f-]{20,}$/i.test(x.id);})";
    private static final String IS_USER = "!!(e.classList.contains('user-message')||e.querySelector('.user-message')||e.querySelector('.chat-user'))";
    private static final String JS_CHAT_MD = "(function(){var m=" + MSGS + ";var out=[];for(var i=0;i<m.length;i++){var e=m[i];var u=" + IS_USER
        + ";var t=(e.innerText||'').trim();if(t)out.push((u?'**You:**':'**Assistant:**')+'\\n\\n'+t);}return out.join('\\n\\n---\\n\\n');})()";
    private static final String JS_LAST_REPLY = "(function(){var m=" + MSGS + ";for(var i=m.length-1;i>=0;i--){var e=m[i];var u=" + IS_USER
        + ";if(!u)return (e.innerText||'').trim();}return '';})()";

    // per-server settings: stored under key|serverUrl, falling back to the old global value
    private String sk(String k) { return k + "|" + prefs.getString("url", ""); }
    private boolean pBool(String k, boolean d) { return prefs.getBoolean(sk(k), prefs.getBoolean(k, d)); }
    private void putBool(String k, boolean v) { prefs.edit().putBoolean(sk(k), v).apply(); }
    private int pInt(String k, int d) { return prefs.getInt(sk(k), prefs.getInt(k, d)); }
    private void putInt(String k, int v) {
        prefs.edit().putInt(sk(k), v).apply();
        if (k.equals("zoom")) web.getSettings().setTextZoom(v);
    }
    private String pStr(String k, String d) { return prefs.getString(sk(k), prefs.getString(k, d)); }
    private void putStr(String k, String v) { prefs.edit().putString(sk(k), v).apply(); }

    private String jsString(String v) {
        if (v == null) return "";
        try {
            Object o = new JSONTokener(v).nextValue();
            return o instanceof String ? ((String) o) : "";
        } catch (Exception e) { return ""; }
    }

    private void openInPanel(String url) {
        communityActive = true;
        community.loadUrl(url);
        panel.setVisibility(View.VISIBLE);
        updateFab();
    }

    // ---- floating tools: mic, speaker, status dot ----
    private void buildTools(FrameLayout root) {
        dot = new View(this);
        GradientDrawable dg = new GradientDrawable();
        dg.setShape(GradientDrawable.OVAL);
        dg.setColor(Color.GRAY);
        dot.setBackground(dg);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(dp(8), dp(8));
        dlp.gravity = Gravity.TOP | Gravity.END;
        dlp.topMargin = dp(3);
        dlp.rightMargin = dp(3);
        root.addView(dot, dlp);
        micFab = toolFab(root, "🎤", "#E11D2E", -dp(30), this::dictate);
        speakFab = toolFab(root, "🔊", "#2B2B2B", dp(30), this::toggleSpeech);
        applyToolVisibility();
    }

    private TextView toolFab(FrameLayout root, String glyph, String color, int offsetY, final Runnable tap) {
        TextView f = new TextView(this);
        f.setText(glyph);
        f.setTextSize(18);
        f.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(Color.parseColor(color));
        f.setBackground(g);
        f.setElevation(dp(4));
        f.setAlpha(0.88f);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(42), dp(42));
        lp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        lp.leftMargin = dp(6);
        f.setTranslationY(offsetY);
        root.addView(f, lp);
        final float[] drag = new float[3];
        f.setOnTouchListener((v, e) -> {
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
                    if (drag[2] == 0) tap.run();
                    return true;
            }
            return false;
        });
        return f;
    }

    private void applyToolVisibility() {
        int vis = prefs.getBoolean("tools", true) ? View.VISIBLE : View.GONE;
        micFab.setVisibility(vis);
        speakFab.setVisibility(vis);
        dot.setVisibility(prefs.getBoolean("health", true) ? View.VISIBLE : View.GONE);
    }

    // ---- server health ----
    private final Runnable healthTick = new Runnable() {
        @Override public void run() {
            checkHealth();
            ui.postDelayed(this, 30000);
        }
    };

    private boolean ping(String u) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(4000);
            c.setRequestMethod("HEAD");
            c.setInstanceFollowRedirects(false);
            c.getResponseCode();
            c.disconnect();
            return true;
        } catch (Exception e) { return false; }
    }

    private void checkHealth() {
        if (!prefs.getBoolean("health", true)) return;
        final String base = prefs.getString("url", null);
        final String fb = prefs.getString("fallback", "");
        if (base == null) return;
        new Thread(() -> {
            final boolean ok = ping(base);
            final boolean fbOk = !ok && !fb.isEmpty() && ping(fb);
            runOnUiThread(() -> {
                ((GradientDrawable) dot.getBackground()).setColor(ok ? Color.parseColor("#22C55E") : fbOk ? Color.parseColor("#F59E0B") : Color.parseColor("#EF4444"));
                if (ok) { failCount = 0; return; }
                failCount++;
                String cur = hostOf(String.valueOf(web.getUrl()));
                if (fbOk && failCount >= 2 && (cur == null || !cur.equals(hostOf(fb)))) {
                    failCount = 0;
                    triedFallback = true;
                    Toast.makeText(this, "Main server unreachable — switched to your fallback address", Toast.LENGTH_LONG).show();
                    web.loadUrl(fb);
                }
            });
        }).start();
    }

    // ---- feedback when a reply finishes ----
    private void onResponseDone(String preview, String url) {
        if (inForeground) {
            if (prefs.getBoolean("haptic", true)) {
                try { ((Vibrator) getSystemService(VIBRATOR_SERVICE)).vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)); } catch (Exception ignored) {}
            }
            if (prefs.getBoolean("sound", false)) {
                try { RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)).play(); } catch (Exception ignored) {}
            }
        }
        archiveCurrent(false);
        if (prefs.getBoolean("autoRead", false)) readLast();
        notifyDone(preview, url);
    }

    // ---- voice: dictation and read-aloud ----
    private void dictate() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your message");
        try { startActivityForResult(i, REQ_SPEECH); }
        catch (Exception e) { Toast.makeText(this, "No speech recognizer found on this phone", Toast.LENGTH_LONG).show(); }
    }

    private void insertIntoChat(String text, final Runnable then) {
        String js = "(function(t){var e=document.getElementById('chat-input')||document.querySelector('textarea')||document.querySelector('[contenteditable=true]');if(!e)return 'none';e.focus();"
            + "if(e.tagName==='TEXTAREA'||e.tagName==='INPUT'){var proto=e.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;var set=Object.getOwnPropertyDescriptor(proto,'value').set;set.call(e,(e.value?e.value+' ':'')+t);e.dispatchEvent(new Event('input',{bubbles:true}));}"
            + "else{document.execCommand('insertText',false,((e.innerText||'').trim()?' ':'')+t);}return 'ok';})(" + JSONObject.quote(text) + ")";
        web.evaluateJavascript(js, r -> {
            if (r != null && r.contains("none")) Toast.makeText(this, "Open a chat first so there's a message box", Toast.LENGTH_LONG).show();
            else if (then != null) then.run();
        });
    }

    private void sendReply(final String text) {
        insertIntoChat(text, () -> web.postDelayed(() -> web.evaluateJavascript(
            "(function(){var b=document.getElementById('send-message-button');if(b){b.click();return 'sent';}return 'no';})()",
            r -> { if (r == null || !r.contains("sent")) Toast.makeText(this, "Couldn't press send — your reply is in the message box", Toast.LENGTH_LONG).show(); }), 600));
    }

    private void readLast() {
        web.evaluateJavascript(JS_LAST_REPLY, v -> {
            String t = jsString(v);
            if (t.isEmpty()) { Toast.makeText(this, "No reply to read yet", Toast.LENGTH_SHORT).show(); return; }
            speakText(t);
        });
    }

    private void toggleSpeech() {
        if (tts != null && ttsReady && tts.isSpeaking()) { tts.stop(); return; }
        readLast();
    }

    private void speakText(String raw) {
        final String text = raw.replaceAll("[*_`#>|~]", "").replaceAll("\\s+", " ").trim();
        if (text.isEmpty()) return;
        if (tts == null) {
            tts = new TextToSpeech(this, status -> {
                ttsReady = status == TextToSpeech.SUCCESS;
                if (ttsReady) { try { tts.setLanguage(Locale.getDefault()); } catch (Exception ignored) {} doSpeak(text); }
                else Toast.makeText(this, "Text-to-speech isn't available on this phone", Toast.LENGTH_LONG).show();
            });
        } else if (ttsReady) doSpeak(text);
    }

    private void doSpeak(String text) {
        int max = Math.max(500, TextToSpeech.getMaxSpeechInputLength() - 100);
        int i = 0, n = 0;
        while (i < text.length()) {
            int e = Math.min(text.length(), i + max);
            if (e < text.length()) { int sp = text.lastIndexOf(' ', e); if (sp > i) e = sp; }
            tts.speak(text.substring(i, e), n == 0 ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, null, "owui" + n);
            n++;
            i = e;
        }
    }

    // ---- share, pin, offline archive ----
    private void shareChat() {
        final String title = web.getTitle();
        web.evaluateJavascript(JS_CHAT_MD, v -> {
            final String t = jsString(v);
            if (t.isEmpty()) { Toast.makeText(this, "Open a chat with messages first", Toast.LENGTH_LONG).show(); return; }
            new AlertDialog.Builder(this).setTitle("Share this chat").setItems(new String[]{"As text", "As a Markdown file"}, (d, w) -> {
                try {
                    if (w == 0) {
                        String s = t.length() > 200000 ? t.substring(0, 200000) : t;
                        startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, s).putExtra(Intent.EXTRA_SUBJECT, title), "Share chat"));
                    } else {
                        File dir = new File(getCacheDir(), "photos");
                        dir.mkdirs();
                        File f = new File(dir, "chat.md");
                        try (FileOutputStream os = new FileOutputStream(f)) { os.write(t.getBytes("UTF-8")); }
                        Uri u = FileProvider.getUriForFile(this, AUTH, f);
                        startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/markdown")
                            .putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share chat"));
                    }
                } catch (Exception e) {
                    Toast.makeText(this, "Couldn't share the chat", Toast.LENGTH_LONG).show();
                }
            }).show();
        });
    }

    private void pinChat() {
        ShortcutManager sm = getSystemService(ShortcutManager.class);
        String url = web.getUrl();
        if (sm == null || !sm.isRequestPinShortcutSupported()) {
            Toast.makeText(this, "Your launcher doesn't support pinned shortcuts", Toast.LENGTH_LONG).show();
            return;
        }
        if (url == null || !isOwnHost(url) || !url.contains("/c/")) {
            Toast.makeText(this, "Open a chat first", Toast.LENGTH_LONG).show();
            return;
        }
        String label = web.getTitle();
        if (label == null || label.trim().isEmpty()) label = "Chat";
        if (label.length() > 24) label = label.substring(0, 24);
        Intent i = new Intent(this, MainActivity.class).setAction(ACTION_OPEN_CHAT).putExtra("url", url)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        ShortcutInfo info = new ShortcutInfo.Builder(this, "chat" + Integer.toHexString(url.hashCode()))
            .setShortLabel(label).setIcon(Icon.createWithResource(this, R.mipmap.ic_launcher)).setIntent(i).build();
        sm.requestPinShortcut(info, null);
    }

    private void archiveCurrent(final boolean manual) {
        final String u = web.getUrl(), title = web.getTitle();
        web.evaluateJavascript(JS_CHAT_MD, v -> {
            String t = jsString(v);
            boolean ok = u != null && u.contains("/c/") && isOwnHost(u) && !t.isEmpty();
            if (ok) ChatArchive.save(this, u, title, t);
            if (manual) Toast.makeText(this, ok ? "Saved for offline reading" : "Open a chat with messages first", Toast.LENGTH_SHORT).show();
        });
    }

    private void showArchive() {
        final List<JSONObject> items = ChatArchive.list(this);
        if (items.isEmpty()) {
            Toast.makeText(this, "No offline chats yet. They're saved automatically when a reply finishes.", Toast.LENGTH_LONG).show();
            return;
        }
        String[] labels = new String[items.size()];
        DateFormat df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
        for (int i = 0; i < labels.length; i++) {
            JSONObject o = items.get(i);
            labels[i] = o.optString("title") + "  ·  " + df.format(new Date(o.optLong("time")));
        }
        new AlertDialog.Builder(this).setTitle("Offline chats").setItems(labels, (d, w) -> viewArchived(items.get(w)))
            .setNegativeButton("Back", (d, w) -> showChatTools()).show();
    }

    private void viewArchived(final JSONObject o) {
        TextView tv = new TextView(this);
        tv.setText(o.optString("text"));
        tv.setTextIsSelectable(true);
        tv.setTextSize(14);
        tv.setPadding(dp(20), dp(12), dp(20), dp(12));
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this).setTitle(o.optString("title")).setView(sv)
            .setPositiveButton("Share", (d, w) -> startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, o.optString("text")), "Share chat")))
            .setNeutralButton("Delete", (d, w) -> { ChatArchive.delete(this, o.optString("url")); showArchive(); })
            .setNegativeButton("Close", null).show();
    }

    // ---- server list backup ----
    private void exportServers() {
        try {
            ensureProfile();
            JSONObject o = new JSONObject();
            o.put("app", "OpenWebUI-Android");
            o.put("profiles", profiles());
            saveBase64("OpenWebUI-servers.json", "application/json", Base64.encodeToString(o.toString(2).getBytes("UTF-8"), Base64.NO_WRAP));
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't export the server list", Toast.LENGTH_LONG).show();
        }
    }

    private void importServersPicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        try { startActivityForResult(i, REQ_IMPORT); }
        catch (Exception e) { Toast.makeText(this, "No file picker available", Toast.LENGTH_LONG).show(); }
    }

    private void importServersFrom(Uri u) {
        try (InputStream is = getContentResolver().openInputStream(u)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            JSONArray in = new JSONObject(bo.toString("UTF-8")).getJSONArray("profiles");
            JSONArray a = profiles();
            int added = 0;
            for (int i = 0; i < in.length(); i++) {
                JSONObject p = in.optJSONObject(i);
                if (p == null || p.optString("url").isEmpty()) continue;
                boolean dup = false;
                for (int j = 0; j < a.length(); j++) {
                    JSONObject q = a.optJSONObject(j);
                    if (q != null && p.optString("url").equals(q.optString("url"))) { dup = true; break; }
                }
                if (!dup) { a.put(p); added++; }
            }
            saveProfiles(a);
            Toast.makeText(this, "Imported " + added + " server" + (added == 1 ? "" : "s"), Toast.LENGTH_LONG).show();
            if (prefs.getString("url", null) == null && a.length() > 0) {
                JSONObject f = a.optJSONObject(0);
                if (f != null) switchTo(f.optString("url"), f.optString("fallback"));
            }
        } catch (Exception e) {
            Toast.makeText(this, "That file isn't a server backup", Toast.LENGTH_LONG).show();
        }
    }
}
