package com.studyagent.client.data.anki.ankidroid

/**
 * GATE 02 — the *one* place that answers "may Study-Agent talk to AnkiDroid?" (§17).
 *
 * Screens, repositories and the study machine never call `checkSelfPermission` themselves; they
 * read the state the detector produced from this component. One question, one owner.
 *
 * ## What kind of permission this actually is (verified, not assumed — §18/§99)
 *
 * AnkiDroid **declares** `com.ichi2.anki.permission.READ_WRITE_DATABASE` itself with
 * `android:protectionLevel="dangerous"` (`AnkiDroid/src/main/AndroidManifest.xml`), and its
 * provider enforces it *dynamically* in code:
 * `CardContentProvider.query()` throws `SecurityException("Permission not granted for: …")` when
 * `checkCallingPermission()` fails. Consequently:
 *
 *  - it is a third-party-declared permission, not an Android-runtime system permission, so there
 *    is no supported interactive "Grant permission" dialog to show a user (§99);
 *  - the grant is resolved **at install/update time**, which is why the official API javadoc
 *    documents `SecurityException` "e.g. due to install order bug" — installing the client before
 *    AnkiDroid means the permission could not be granted when the client was installed;
 *  - the remedy is therefore a reinstall/update of Study-Agent (or of AnkiDroid), never a
 *    runtime request prompt — see [AnkiDroidGuidance].
 *
 * [AnkiDroidPermissionState.protectionLevel] records what the platform reports at runtime, so
 * diagnostics can prove this without relying on the paragraph above.
 */
data class AnkiDroidPermissionState(
    /** Permission name that was checked (public contract string, not sensitive). */
    val permission: String,
    val granted: Boolean,
    /**
     * Platform-reported protection level of [permission] (`normal`, `dangerous`, `signature`,
     * `signatureOrSystem`, `unknown`), or `null` when the permission is not visible to us —
     * typically because the expected AnkiDroid package is not installed.
     */
    val protectionLevel: String? = null,
    /** True when the permission is declared by an installed package we can see. */
    val declaredByInstalledPackage: Boolean = false
)

/** Abstraction so the detector is testable without Android (§64). */
interface AnkiDroidPermissionManager {

    /** Current permission state for [endpoint]. Never throws for a normal "not granted". */
    suspend fun state(endpoint: AnkiDroidEndpoint): AnkiDroidPermissionState
}
