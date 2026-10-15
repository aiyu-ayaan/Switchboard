package com.switchboard.app.mirror

import android.app.Activity
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * A see-through activity whose only job is to show Android's own
 * "start recording or casting?" prompt and hand the answer on.
 *
 * The prompt can only be launched for a result from an activity, and the
 * desktop's request can arrive while the user is anywhere on the phone, so it
 * cannot live in the main activity: this one appears over whatever is on
 * screen, asks, and is gone.
 */
class MirrorConsentActivity : ComponentActivity() {

    private val prompt = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        MirrorController.get(this).onConsent(
            granted = result.resultCode == Activity.RESULT_OK,
            resultCode = result.resultCode,
            data = result.data
        )
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return // the prompt is already up
        val manager = getSystemService(MediaProjectionManager::class.java)
        runCatching { prompt.launch(manager.createScreenCaptureIntent()) }
            .onFailure {
                MirrorController.get(this).onConsent(false, Activity.RESULT_CANCELED, null)
                finish()
            }
    }
}
