// Re-implementation of AGP's MockableJarGenerator (returnDefaultValues = true) using the ASM
// shaded inside the Kotlin compiler. Every method body returns the default value for its type.
package mockgen
import org.jetbrains.org.objectweb.asm.*
import org.jetbrains.org.objectweb.asm.tree.*
import java.util.zip.*
import java.io.*

fun main(args: Array<String>) {
    val input = ZipFile(args[0]); val out = ZipOutputStream(FileOutputStream(args[1]))
    for (e in input.entries()) {
        if (e.isDirectory) continue
        val bytes = input.getInputStream(e).readBytes()
        val data = if (e.name.endsWith(".class")) transform(bytes) else bytes
        out.putNextEntry(ZipEntry(e.name)); out.write(data); out.closeEntry()
    }
    out.close()
}

fun transform(bytes: ByteArray): ByteArray {
    val node = ClassNode(); ClassReader(bytes).accept(node, 0)
    node.access = node.access and Opcodes.ACC_FINAL.inv()
    for (m in node.methods) {
        m.access = m.access and Opcodes.ACC_FINAL.inv()
        if (m.access and (Opcodes.ACC_ABSTRACT or Opcodes.ACC_NATIVE) != 0) {
            if (m.access and Opcodes.ACC_NATIVE != 0) m.access = m.access and Opcodes.ACC_NATIVE.inv() else continue
        }
        if (m.name == "<init>" || m.name == "<clinit>") {
            // keep constructors callable: call super with defaults is complex; keep original but strip throws
            // Stubs' ctors are `throw new RuntimeException("Stub!")`. Replace with super() when possible.
            if (m.name == "<clinit>") { m.instructions = InsnList().apply { add(InsnNode(Opcodes.RETURN)) }; m.tryCatchBlocks.clear(); m.localVariables?.clear(); continue }
            val first = m.instructions.toArray().firstOrNull { it is MethodInsnNode && it.opcode == Opcodes.INVOKESPECIAL && it.name == "<init>" } ?: continue
            val kept = InsnList()
            for (insn in m.instructions.toArray()) {
                if (insn is LabelNode || insn is LineNumberNode || insn is FrameNode) continue
                kept.add(insn.clone(HashMap()))
                if (insn === first) break
            }
            kept.add(InsnNode(Opcodes.RETURN))
            m.instructions = kept
            m.tryCatchBlocks.clear(); m.localVariables?.clear(); continue
        }
        val ret = Type.getReturnType(m.desc)
        m.instructions = InsnList().apply {
            when (ret.sort) {
                Type.VOID -> add(InsnNode(Opcodes.RETURN))
                Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT -> { add(InsnNode(Opcodes.ICONST_0)); add(InsnNode(Opcodes.IRETURN)) }
                Type.LONG -> { add(InsnNode(Opcodes.LCONST_0)); add(InsnNode(Opcodes.LRETURN)) }
                Type.FLOAT -> { add(InsnNode(Opcodes.FCONST_0)); add(InsnNode(Opcodes.FRETURN)) }
                Type.DOUBLE -> { add(InsnNode(Opcodes.DCONST_0)); add(InsnNode(Opcodes.DRETURN)) }
                else -> { add(InsnNode(Opcodes.ACONST_NULL)); add(InsnNode(Opcodes.ARETURN)) }
            }
        }
        m.tryCatchBlocks.clear(); m.localVariables?.clear()
    }
    // Superclass no-arg ctor may not exist; COMPUTE_MAXS only (no frames needed for simple bodies? need frames for v50+)
    val w = object : ClassWriter(ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES) {
        override fun getCommonSuperClass(a: String, b: String) = "java/lang/Object"
    }
    node.accept(w); return w.toByteArray()
}

object StripMetadata {
    @JvmStatic fun main(args: Array<String>) {
        for (path in args) {
            val f = File(path); val node = ClassNode(); ClassReader(f.readBytes()).accept(node, 0)
            node.visibleAnnotations = node.visibleAnnotations?.filter { it.desc != "Lkotlin/Metadata;" }?.toMutableList()
            val w = ClassWriter(0); node.accept(w); f.writeBytes(w.toByteArray())
        }
    }
}
