package com.openwebui.app;

import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

public class RecentChatsService extends RemoteViewsService {
    @Override public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new Factory(getApplicationContext());
    }

    static class Factory implements RemoteViewsFactory {
        private final Context ctx;
        private List<JSONObject> items = new ArrayList<>();

        Factory(Context c) { ctx = c; }

        @Override public void onCreate() {}
        @Override public void onDataSetChanged() {
            List<JSONObject> all = ChatArchive.list(ctx);
            items = all.size() > 15 ? new ArrayList<>(all.subList(0, 15)) : all;
        }
        @Override public void onDestroy() { items.clear(); }
        @Override public int getCount() { return items.size(); }
        @Override public RemoteViews getViewAt(int i) {
            RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_recent_item);
            if (i < 0 || i >= items.size()) return rv;
            JSONObject o = items.get(i);
            rv.setTextViewText(R.id.item_title, o.optString("title"));
            rv.setOnClickFillInIntent(R.id.item_title, new Intent().putExtra("url", o.optString("url")));
            return rv;
        }
        @Override public RemoteViews getLoadingView() { return null; }
        @Override public int getViewTypeCount() { return 1; }
        @Override public long getItemId(int i) { return i; }
        @Override public boolean hasStableIds() { return false; }
    }
}
