package com.langamestudio.lanpartyarcade;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.TextView;

/** Minimal launcher used to verify the Android project skeleton before game work begins. */
public final class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        TextView status = new TextView(this);
        status.setText("LAN Arcade\nAndroid build skeleton");
        status.setTextSize(24);
        status.setGravity(Gravity.CENTER);
        status.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(status);
    }
}
