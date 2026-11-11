package com.harley.marchhelper;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;

/** Invisible activity: asks Android for screen-capture permission, then hands it to the service. */
public class CaptureActivity extends Activity {

    private static final int REQ = 7;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Intent i = new Intent(this, OverlayService.class).setAction(OverlayService.ACTION_PROJECTION);
        i.putExtra(OverlayService.EXTRA_CODE, resultCode);
        if (data != null) i.putExtra(OverlayService.EXTRA_DATA, data);
        startService(i);
        finish();
        overridePendingTransition(0, 0);
    }
}
