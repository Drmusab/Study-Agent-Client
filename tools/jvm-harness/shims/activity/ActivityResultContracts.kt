// Harness stub — androidx.activity.result.contract. See ../compose/Runtime.kt for the policy.
package androidx.activity.result.contract

import android.content.Context
import android.content.Intent
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultContract

class ActivityResultContracts {
    class RequestPermission : ActivityResultContract<String, Boolean>() {
        override fun createIntent(context: Context, input: String): Intent = Intent()
        override fun parseResult(resultCode: Int, intent: Intent?): Boolean = false
    }

    class RequestMultiplePermissions : ActivityResultContract<Array<String>, Map<String, Boolean>>() {
        override fun createIntent(context: Context, input: Array<String>): Intent = Intent()
        override fun parseResult(resultCode: Int, intent: Intent?): Map<String, Boolean> = emptyMap()
    }

    class StartActivityForResult : ActivityResultContract<Unit, ActivityResult>() {
        override fun createIntent(context: Context, input: Unit): Intent = Intent()
        override fun parseResult(resultCode: Int, intent: Intent?): ActivityResult =
            ActivityResult(resultCode, intent)
    }

    /** GATE 18 — one content picker; the MIME type is the input, the picked URI the output. */
    class GetContent : ActivityResultContract<String, android.net.Uri?>() {
        override fun createIntent(context: Context, input: String): Intent = Intent()
        override fun parseResult(resultCode: Int, intent: Intent?): android.net.Uri? = null
    }
}
