package com.maydings.vibelauncher;

import android.app.Activity;
import android.os.Bundle;

/**
 * An invisible screen that closes right away. HomeKeyService opens it once at boot: whatever was in front
 * comes back when it closes and says so, so the service can tell the stock home from an HDMI input without
 * reading the screen.
 */
public class ProbeActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().post(this::close);
    }

    private void close() {
        finish();
        overridePendingTransition(0, 0);
    }
}
