// Harness runner: discovers @org.junit.Test methods in a class directory and runs them.
package harness
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier

fun main(args: Array<String>) {
    val root = File(args[0])
    val filter = args.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { Regex(it) }
    val loader = Thread.currentThread().contextClassLoader
    val names = root.walkTopDown().filter { it.isFile && it.name.endsWith(".class") && !it.name.contains('$') }
        .map { it.relativeTo(root).path.removeSuffix(".class").replace(File.separatorChar, '.') }.sorted().toList()
    var passed = 0; var failed = 0; var ignored = 0; var classes = 0
    val failures = mutableListOf<String>()
    val started = System.nanoTime()
    for (name in names) {
        if (filter != null && !filter.containsMatchIn(name)) continue
        val cls = try { Class.forName(name, false, loader) } catch (t: Throwable) { continue }
        if (Modifier.isAbstract(cls.modifiers) || cls.isInterface) continue
        val tests = cls.methods.filter { it.isAnnotationPresent(org.junit.Test::class.java) }.sortedBy { it.name }
        if (tests.isEmpty()) continue
        classes++
        val befores = cls.methods.filter { it.isAnnotationPresent(org.junit.Before::class.java) }
        val afters = cls.methods.filter { it.isAnnotationPresent(org.junit.After::class.java) }
        val classIgnored = cls.isAnnotationPresent(org.junit.Ignore::class.java)
        for (m in tests) {
            if (classIgnored || m.isAnnotationPresent(org.junit.Ignore::class.java)) { ignored++; continue }
            val ann = m.getAnnotation(org.junit.Test::class.java)
            val expected = ann.expected.java.takeIf { it != org.junit.Test.None::class.java }
            var error: Throwable? = null
            val limit = if (ann.timeout > 0) ann.timeout else (System.getProperty("harness.timeoutMs")?.toLong() ?: 60_000L)
            val worker = Thread {
                try {
                    val inst = cls.getDeclaredConstructor().newInstance()
                    try {
                        befores.forEach { it.invoke(inst) }
                        try { m.invoke(inst) } catch (e: InvocationTargetException) { throw e.targetException }
                        if (expected != null) error = AssertionError("Expected exception: ${expected.name}")
                    } catch (t: Throwable) {
                        val cause = if (t is InvocationTargetException) t.targetException else t
                        if (expected == null || !expected.isInstance(cause)) error = cause
                    } finally {
                        try { afters.forEach { it.invoke(inst) } } catch (t: Throwable) { if (error == null) error = (t as? InvocationTargetException)?.targetException ?: t }
                    }
                } catch (t: Throwable) { error = (t as? InvocationTargetException)?.targetException ?: t }
            }
            worker.isDaemon = true
            val t0 = System.nanoTime()
            worker.start(); worker.join(limit)
            if (worker.isAlive) { error = AssertionError("HARNESS TIMEOUT after ${limit}ms").also { it.stackTrace = worker.stackTrace }; worker.interrupt() }
            val ms = (System.nanoTime() - t0) / 1_000_000
            println((if (error == null) "PASS " else "FAIL ") + "${cls.name}.${m.name} (${ms}ms)")
            System.out.flush()
            if (error == null) passed++ else {
                failed++
                val trace = error.stackTraceToString().lines().take(14).joinToString("\n")
                failures += "FAIL ${cls.name}.${m.name}\n$trace"
            }
        }
    }
    failures.forEach { println(it); println() }
    val secs = (System.nanoTime() - started) / 1e9
    println("RESULT classes=$classes tests=${passed + failed + ignored} passed=$passed failed=$failed ignored=$ignored time=%.1fs".format(secs))
    System.exit(if (failed == 0) 0 else 1)
}
