package com.openwebui.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

public class NewChatWidget extends AppWidgetProvider {
    @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        for (int id : ids) {
            RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget_new_chat);
            Intent chat = new Intent(c, MainActivity.class).setAction(MainActivity.ACTION_NEW_CHAT)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            Intent ask = new Intent(c, QuickPromptActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            int fl = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
            rv.setOnClickPendingIntent(R.id.widget_new, PendingIntent.getActivity(c, 0, chat, fl));
            rv.setOnClickPendingIntent(R.id.widget_ask, PendingIntent.getActivity(c, 1, ask, fl));
            m.updateAppWidget(id, rv);
        }
    }
}
