package com.openwebui.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.widget.RemoteViews;

/** Home-screen list of your most recent chats; tap one to open it. */
public class RecentChatsWidget extends AppWidgetProvider {
    @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        for (int id : ids) {
            RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget_recent);
            Intent svc = new Intent(c, RecentChatsService.class);
            svc.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
            svc.setData(Uri.parse(svc.toUri(Intent.URI_INTENT_SCHEME)));
            rv.setRemoteAdapter(R.id.recent_list, svc);
            rv.setEmptyView(R.id.recent_list, R.id.recent_empty);
            Intent open = new Intent(c, MainActivity.class).setAction(MainActivity.ACTION_OPEN_CHAT)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int mutable = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
            rv.setPendingIntentTemplate(R.id.recent_list, PendingIntent.getActivity(c, 10, open, PendingIntent.FLAG_UPDATE_CURRENT | mutable));
            Intent chat = new Intent(c, MainActivity.class).setAction(MainActivity.ACTION_NEW_CHAT)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            rv.setOnClickPendingIntent(R.id.recent_new, PendingIntent.getActivity(c, 11, chat, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            m.updateAppWidget(id, rv);
        }
        super.onUpdate(c, m, ids);
    }
}
