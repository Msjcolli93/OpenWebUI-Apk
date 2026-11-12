package com.openwebui.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;

/** Small dialog opened from the widget: type a question and it starts a new chat running. */
public class QuickPromptActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        float d = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * d);
        box.setPadding(pad, pad, pad, pad);
        final EditText et = new EditText(this);
        et.setHint("Ask Open WebUI…");
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        et.setMinLines(2);
        Button send = new Button(this);
        send.setText("Send");
        box.addView(et, new LinearLayout.LayoutParams(-1, -2));
        box.addView(send, new LinearLayout.LayoutParams(-1, -2));
        setContentView(box);
        send.setOnClickListener(v -> {
            String t = et.getText().toString().trim();
            if (t.isEmpty()) return;
            Intent i = new Intent(this, MainActivity.class).setAction(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, t).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
            finish();
        });
        et.requestFocus();
    }
}
