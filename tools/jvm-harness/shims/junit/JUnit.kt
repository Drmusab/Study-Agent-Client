// Harness stub: the org.junit 4 API subset used by this repository, plus a tiny reflective runner.
package org.junit
import kotlin.reflect.KClass

@Retention(AnnotationRetention.RUNTIME) @Target(AnnotationTarget.FUNCTION)
annotation class Test(val expected: KClass<out Throwable> = None::class, val timeout: Long = 0L) {
    class None : Throwable()
}
@Retention(AnnotationRetention.RUNTIME) @Target(AnnotationTarget.FUNCTION) annotation class Before
@Retention(AnnotationRetention.RUNTIME) @Target(AnnotationTarget.FUNCTION) annotation class After
@Retention(AnnotationRetention.RUNTIME) @Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS) annotation class Ignore(val value: String = "")

fun interface ThrowingRunnable { fun run() }

object Assert {
    private fun eq(a: Any?, b: Any?): Boolean = when {
        a === b -> true
        a == null || b == null -> false
        a is Array<*> && b is Array<*> -> a.contentDeepEquals(b)
        else -> a == b
    }
    private fun fmt(message: String?, e: Any?, a: Any?) = (if (message != null) "$message " else "") + "expected:<$e> but was:<$a>"
    @JvmStatic fun fail(message: String?): Nothing = throw AssertionError(message)
    @JvmStatic fun fail(): Nothing = throw AssertionError()
    @JvmStatic fun assertTrue(message: String?, condition: Boolean) { if (!condition) fail(message) }
    @JvmStatic fun assertTrue(condition: Boolean) = assertTrue(null, condition)
    @JvmStatic fun assertFalse(message: String?, condition: Boolean) = assertTrue(message, !condition)
    @JvmStatic fun assertFalse(condition: Boolean) = assertFalse(null, condition)
    @JvmStatic fun assertEquals(message: String?, expected: Any?, actual: Any?) { if (!eq(expected, actual)) throw AssertionError(fmt(message, expected, actual)) }
    @JvmStatic fun assertEquals(expected: Any?, actual: Any?) = assertEquals(null, expected, actual)
    @JvmStatic fun assertEquals(message: String?, expected: Long, actual: Long) { if (expected != actual) throw AssertionError(fmt(message, expected, actual)) }
    @JvmStatic fun assertEquals(expected: Long, actual: Long) = assertEquals(null, expected, actual)
    @JvmStatic fun assertEquals(message: String?, expected: Double, actual: Double, delta: Double) {
        if (expected.compareTo(actual) != 0 && Math.abs(expected - actual) > delta) throw AssertionError(fmt(message, expected, actual))
    }
    @JvmStatic fun assertEquals(expected: Double, actual: Double, delta: Double) = assertEquals(null, expected, actual, delta)
    @JvmStatic fun assertEquals(message: String?, expected: Float, actual: Float, delta: Float) {
        if (expected.compareTo(actual) != 0 && Math.abs(expected - actual) > delta) throw AssertionError(fmt(message, expected, actual))
    }
    @JvmStatic fun assertEquals(expected: Float, actual: Float, delta: Float) = assertEquals(null, expected, actual, delta)
    @JvmStatic fun assertNotEquals(message: String?, unexpected: Any?, actual: Any?) { if (eq(unexpected, actual)) fail((message?.plus(" ") ?: "") + "Values should be different. Actual: $actual") }
    @JvmStatic fun assertNotEquals(unexpected: Any?, actual: Any?) = assertNotEquals(null, unexpected, actual)
    @JvmStatic fun assertNotEquals(message: String?, unexpected: Long, actual: Long) = assertNotEquals(message, unexpected as Any, actual as Any)
    @JvmStatic fun assertNotEquals(unexpected: Long, actual: Long) = assertNotEquals(null, unexpected, actual)
    @JvmStatic fun assertNull(message: String?, o: Any?) { if (o != null) fail((message?.plus(" ") ?: "") + "expected null, but was:<$o>") }
    @JvmStatic fun assertNull(o: Any?) = assertNull(null, o)
    @JvmStatic fun assertNotNull(message: String?, o: Any?) { if (o == null) fail(message ?: "expected not null") }
    @JvmStatic fun assertNotNull(o: Any?) = assertNotNull(null, o)
    @JvmStatic fun assertSame(message: String?, e: Any?, a: Any?) { if (e !== a) throw AssertionError(fmt(message, e, a)) }
    @JvmStatic fun assertSame(e: Any?, a: Any?) = assertSame(null, e, a)
    @JvmStatic fun assertNotSame(message: String?, e: Any?, a: Any?) { if (e === a) fail(message ?: "expected not same") }
    @JvmStatic fun assertNotSame(e: Any?, a: Any?) = assertNotSame(null, e, a)
    @JvmStatic fun assertArrayEquals(message: String?, e: ByteArray?, a: ByteArray?) { if (!(e contentEquals a)) fail(message ?: "arrays differ") }
    @JvmStatic fun assertArrayEquals(e: ByteArray?, a: ByteArray?) = assertArrayEquals(null, e, a)
    @JvmStatic fun assertArrayEquals(message: String?, e: Array<*>?, a: Array<*>?) { if (!(e contentDeepEquals a)) fail(message ?: "arrays differ") }
    @JvmStatic fun assertArrayEquals(e: Array<*>?, a: Array<*>?) = assertArrayEquals(null, e, a)
    @JvmStatic fun <T : Throwable> assertThrows(message: String?, expected: Class<T>, runnable: ThrowingRunnable): T {
        try { runnable.run() } catch (t: Throwable) {
            if (expected.isInstance(t)) return expected.cast(t)
            throw AssertionError((message?.plus(" ") ?: "") + "unexpected exception type thrown; expected:<${expected.simpleName}> but was:<${t.javaClass.simpleName}>", t)
        }
        throw AssertionError((message?.plus(" ") ?: "") + "expected ${expected.simpleName} to be thrown, but nothing was thrown")
    }
    @JvmStatic fun <T : Throwable> assertThrows(expected: Class<T>, runnable: ThrowingRunnable): T = assertThrows(null, expected, runnable)
}
