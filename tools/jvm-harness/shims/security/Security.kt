// Harness stub mirroring the real security-crypto 1.1.0-alpha06 static signatures.
package androidx.security.crypto
import android.content.Context
import android.content.SharedPreferences
object MasterKeys {
    @JvmField val AES256_GCM_SPEC: Any = Any()
    @JvmStatic fun getOrCreate(spec: Any): String = throw UnsupportedOperationException("security stub")
}
object EncryptedSharedPreferences {
    enum class PrefKeyEncryptionScheme { AES256_SIV }
    enum class PrefValueEncryptionScheme { AES256_GCM }
    @JvmStatic fun create(fileName: String, masterKeyAlias: String, context: Context, k: PrefKeyEncryptionScheme, v: PrefValueEncryptionScheme): SharedPreferences =
        throw UnsupportedOperationException("security stub")
}
