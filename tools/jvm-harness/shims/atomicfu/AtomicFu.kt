package kotlinx.atomicfu

class AtomicRef<T>(initial: T) {
    private val ref = java.util.concurrent.atomic.AtomicReference(initial)
    var value: T get() = ref.get(); set(v) { ref.set(v) }
    fun compareAndSet(expect: T, update: T): Boolean = ref.compareAndSet(expect, update)
    fun getAndSet(v: T): T = ref.getAndSet(v)
}
class AtomicInt(initial: Int) {
    private val ref = java.util.concurrent.atomic.AtomicInteger(initial)
    var value: Int get() = ref.get(); set(v) { ref.set(v) }
    fun compareAndSet(e: Int, u: Int) = ref.compareAndSet(e, u)
    fun getAndSet(v: Int) = ref.getAndSet(v)
    fun incrementAndGet() = ref.incrementAndGet()
    fun decrementAndGet() = ref.decrementAndGet()
    fun getAndIncrement() = ref.getAndIncrement()
    fun getAndDecrement() = ref.getAndDecrement()
}
class AtomicLong(initial: Long) {
    private val ref = java.util.concurrent.atomic.AtomicLong(initial)
    var value: Long get() = ref.get(); set(v) { ref.set(v) }
    fun compareAndSet(e: Long, u: Long) = ref.compareAndSet(e, u)
    fun getAndSet(v: Long) = ref.getAndSet(v)
    fun incrementAndGet() = ref.incrementAndGet()
    fun decrementAndGet() = ref.decrementAndGet()
    fun getAndIncrement() = ref.getAndIncrement()
}
class AtomicBoolean(initial: Boolean) {
    private val ref = java.util.concurrent.atomic.AtomicBoolean(initial)
    var value: Boolean get() = ref.get(); set(v) { ref.set(v) }
    fun compareAndSet(e: Boolean, u: Boolean) = ref.compareAndSet(e, u)
    fun getAndSet(v: Boolean) = ref.getAndSet(v)
}
fun <T> atomic(initial: T): AtomicRef<T> = AtomicRef(initial)
fun atomic(initial: Int): AtomicInt = AtomicInt(initial)
fun atomic(initial: Long): AtomicLong = AtomicLong(initial)
fun atomic(initial: Boolean): AtomicBoolean = AtomicBoolean(initial)
