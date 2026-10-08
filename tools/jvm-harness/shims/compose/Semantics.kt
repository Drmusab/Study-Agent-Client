// Harness stub — androidx.compose.ui.semantics. See Runtime.kt for the policy.
//
// Deliberately narrow, because it is the reason this harness exists: `Modifier.semantics` takes
// `mergeDescendants` as a *parameter*, and there is no importable `mergeDescendants` symbol in this
// package. An app file that imports it must fail, exactly as `compileDebugKotlin` would.
package androidx.compose.ui.semantics

import androidx.compose.ui.Modifier

class SemanticsPropertyReceiver {
    internal val props = LinkedHashMap<String, Any?>()
}

class SemanticsPropertyKey<T>(val name: String)

interface SemanticsPropertyReceiverMarker

var SemanticsPropertyReceiver.contentDescription: String?
    get() = props["contentDescription"] as String?
    set(value) { props["contentDescription"] = value }

var SemanticsPropertyReceiver.label: String?
    get() = props["label"] as String?
    set(value) { props["label"] = value }

var SemanticsPropertyReceiver.stateDescription: String?
    get() = props["stateDescription"] as String?
    set(value) { props["stateDescription"] = value }

var SemanticsPropertyReceiver.selected: Boolean
    get() = props["selected"] as? Boolean ?: false
    set(value) { props["selected"] = value }

var SemanticsPropertyReceiver.role: Role?
    get() = props["role"] as Role?
    set(value) { props["role"] = value }

var SemanticsPropertyReceiver.onProfileChanged: ((Any) -> Unit)?
    get() = null
    set(_) {}

fun SemanticsPropertyReceiver.onClick(action: () -> Unit) { props["onClick"] = action }
fun SemanticsPropertyReceiver.onLongClick(action: () -> Unit) { props["onLongClick"] = action }
fun SemanticsPropertyReceiver.onExpand(action: () -> Unit) { props["onExpand"] = action }
fun SemanticsPropertyReceiver.onCollapse(action: () -> Unit) { props["onCollapse"] = action }
fun SemanticsPropertyReceiver.hideFromAccessibility(hide: Boolean = true) { props["hide"] = hide }

@JvmInline
value class Role(val value: Int) {
    companion object {
        val Button = Role(1)
        val Checkbox = Role(2)
        val Switch = Role(3)
        val Tab = Role(4)
        val RadioButton = Role(5)
        val Image = Role(6)
        val DropdownList = Role(7)
        val Picker = Role(8)
        val Heading = Role(9)
    }
}

@JvmInline
value class LiveRegionMode(val value: Int) {
    companion object {
        val None = LiveRegionMode(0)
        val Polite = LiveRegionMode(1)
        val Assertive = LiveRegionMode(2)
    }
}

fun Modifier.semantics(
    mergeDescendants: Boolean = false,
    properties: SemanticsPropertyReceiver.() -> Unit
): Modifier = this

fun Modifier.clearAndSetSemantics(properties: SemanticsPropertyReceiver.() -> Unit): Modifier = this
