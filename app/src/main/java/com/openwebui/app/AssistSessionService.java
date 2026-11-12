package com.openwebui.app;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.service.voice.VoiceInteractionSession;
import android.service.voice.VoiceInteractionSessionService;

public class AssistSessionService extends VoiceInteractionSessionService {
    @Override public VoiceInteractionSession onNewSession(Bundle args) {
        return new Session(this);
    }

    static class Session extends VoiceInteractionSession {
        Session(Context c) { super(c); }

        @Override public void onShow(Bundle args, int flags) {
            super.onShow(args, flags);
            Intent i = new Intent(getContext(), MainActivity.class).setAction(MainActivity.ACTION_NEW_CHAT)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startAssistantActivity(i);
            hide();
        }
    }
}
