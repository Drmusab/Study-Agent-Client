// Harness stub — androidx.activity.result. See ../compose/Runtime.kt for the policy.
package androidx.activity.result

import android.content.Intent

class ActivityResult(val resultCode: Int = 0, val data: Intent? = null) {
    fun <T> resultValue(): T? = null
}

fun interface ActivityResultCallback<O> {
    fun onActivityResult(result: O)
}

abstract class ActivityResultLauncher<I> {
    val key: String = ""
    abstract fun launch(input: I)
    fun unregister() = Unit
}

abstract class ActivityResultContract<I, O> {
    abstract fun createIntent(context: android.content.Context, input: I): Intent
    abstract fun parseResult(resultCode: Int, intent: Intent?): O
}

interface ActivityResultCaller {
    fun <I, O> registerForActivityResult(
        contract: ActivityResultContract<I, O>,
        callback: ActivityResultCallback<O>
    ): ActivityResultLauncher<I>
}

class ActivityResultRegistry
