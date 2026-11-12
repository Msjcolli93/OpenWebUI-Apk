package com.openwebui.app;

import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.json.JSONObject;

/** Small on-device cache of recent chats (text only), used for offline reading and the recent-chats widget. */
public class ChatArchive {
    private static final int MAX = 50;

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "archive");
        d.mkdirs();
        return d;
    }

    private static File file(Context c, String url) {
        return new File(dir(c), Integer.toHexString(url.hashCode()) + ".json");
    }

    public static void save(Context c, String url, String title, String text) {
        if (url == null || text == null || text.trim().isEmpty()) return;
        try {
            JSONObject o = new JSONObject();
            o.put("url", url);
            o.put("title", title == null || title.trim().isEmpty() ? "Chat" : title.trim());
            o.put("time", System.currentTimeMillis());
            o.put("text", text);
            try (FileOutputStream os = new FileOutputStream(file(c, url))) { os.write(o.toString().getBytes("UTF-8")); }
            File[] all = dir(c).listFiles();
            if (all != null && all.length > MAX) {
                Arrays.sort(all, Comparator.comparingLong(File::lastModified));
                for (int i = 0; i < all.length - MAX; i++) all[i].delete();
            }
            refreshWidget(c);
        } catch (Exception ignored) {}
    }

    public static List<JSONObject> list(Context c) {
        List<JSONObject> out = new ArrayList<>();
        File[] all = dir(c).listFiles();
        if (all == null) return out;
        for (File f : all) {
            try (FileInputStream is = new FileInputStream(f)) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
                out.add(new JSONObject(bo.toString("UTF-8")));
            } catch (Exception ignored) {}
        }
        Collections.sort(out, (a, b) -> Long.compare(b.optLong("time"), a.optLong("time")));
        return out;
    }

    public static void delete(Context c, String url) {
        if (url != null) file(c, url).delete();
        refreshWidget(c);
    }

    public static void refreshWidget(Context c) {
        try {
            AppWidgetManager am = AppWidgetManager.getInstance(c);
            int[] ids = am.getAppWidgetIds(new ComponentName(c, RecentChatsWidget.class));
            if (ids.length > 0) am.notifyAppWidgetViewDataChanged(ids, R.id.recent_list);
        } catch (Exception ignored) {}
    }
}
