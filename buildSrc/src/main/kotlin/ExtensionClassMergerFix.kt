import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.VarInsnNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.FrameNode
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

/**
 * Fix reversed member comparisons in the pinned Morphe and ReVanced SDKs.
 * https://github.com/MorpheApp/morphe-patcher/blob/v2.0.0/src/main/kotlin/app/morphe/patcher/util/ClassMerger.kt
 * https://github.com/ReVanced/revanced-patcher/blob/v21.0.0/src/main/kotlin/app/revanced/patcher/util/ClassMerger.kt
 *
 * Filter incoming members against existing members, retaining the SDK's signature
 * matching, implementations, access handling and class registration.
 */
object ExtensionClassMergerFix {
    private const val CLASS_DEF = "com/android/tools/smali/dexlib2/iface/ClassDef"
    private const val MERGE_DESCRIPTOR = "(L$CLASS_DEF;L$CLASS_DEF;)L$CLASS_DEF;"
    private val entries = setOf(
        "app/morphe/patcher/util/ClassMerger.class",
        "app/revanced/patcher/util/ClassMerger.class"
    )

    @JvmStatic
    fun patchClass(bytes: ByteArray): ByteArray {
        val reader = ClassReader(bytes)
        val node = ClassNode()
        reader.accept(node, 0)
        check(node.name + ".class" in entries) { "Unexpected patcher merger class" }
        val morphe = node.name.startsWith("app/morphe/")
        for ((methodName, getter) in listOf(
            "addMissingFields" to "getFields",
            "addMissingMethods" to "getMethods"
        )) {
            val method = node.methods.single { it.name == methodName && it.desc == MERGE_DESCRIPTOR }
            val receivers = method.instructions.toArray()
                .filterIsInstance<MethodInsnNode>()
                .filter { it.owner == CLASS_DEF && it.name == getter && it.desc == "()Ljava/lang/Iterable;" }
                .map { call ->
                    var previous = call.previous
                    while (previous != null && previous.opcode < 0) previous = previous.previous
                    check(previous is VarInsnNode && previous.opcode == Opcodes.ALOAD) {
                        "Unexpected receiver in $methodName; review SDK compatibility"
                    }
                    previous
                }
            val slots = receivers.map { it.`var` }
            // Morphe hashes existing members before filtering; ReVanced's field
            // helper filters the first collection directly against the second.
            val corrected = if (getter == "getFields" && !morphe) listOf(2, 1) else listOf(1, 2)
            check(slots == corrected || slots == corrected.reversed()) {
                "Unexpected $methodName layout: $slots; review SDK compatibility"
            }
            receivers[0].`var` = corrected[0]
            receivers[1].`var` = corrected[1]
            // Morphe bundles repair the app's obfuscated protobuf API themselves.
            // Importing extension protobuf members first changes fingerprint match counts
            // and mixes incompatible obfuscated and unobfuscated method signatures.
            if (morphe && method.instructions.toArray().none {
                    it is LdcInsnNode && it.cst == "Lcom/google/protobuf/"
                }) {
                val guard = InsnList()
                for (prefix in listOf("Lcom/google/protobuf/", "Lcom/google/protos/")) {
                    val next = LabelNode()
                    guard.add(VarInsnNode(Opcodes.ALOAD, 1))
                    guard.add(MethodInsnNode(Opcodes.INVOKEINTERFACE, CLASS_DEF, "getType", "()Ljava/lang/String;", true))
                    guard.add(LdcInsnNode(prefix))
                    guard.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "startsWith", "(Ljava/lang/String;)Z", false))
                    guard.add(JumpInsnNode(Opcodes.IFEQ, next))
                    guard.add(VarInsnNode(Opcodes.ALOAD, 1))
                    guard.add(InsnNode(Opcodes.ARETURN))
                    guard.add(next)
                    guard.add(FrameNode(Opcodes.F_SAME, 0, null, 0, null))
                }
                method.instructions.insert(guard)
                method.maxStack = maxOf(method.maxStack, 2)
            }
        }
        // Existing frames remain valid; the early-return guards supply their own empty-stack frames.
        return ClassWriter(reader, 0).also { node.accept(it) }.toByteArray()
    }

    /** ReVanced 22's Android variant keeps the same merger inside classes.jar. */
    @JvmStatic
    fun patchAar(input: File, output: File) {
        val original = File.createTempFile("patcher-classes-", ".jar", output.parentFile)
        val corrected = File(original.path + ".corrected.jar")
        try {
            JarFile(input).use { aar ->
                val classes = checkNotNull(aar.getJarEntry("classes.jar")) {
                    "Missing classes.jar in ${input.name}"
                }
                aar.getInputStream(classes).use { source ->
                    original.outputStream().use { source.copyTo(it) }
                }
                patchJar(original, corrected)
                JarOutputStream(output.outputStream().buffered()).use { result ->
                    for (entry in aar.entries()) {
                        result.putNextEntry(JarEntry(entry.name).apply { time = 0L })
                        if (!entry.isDirectory) {
                            if (entry.name == "classes.jar") corrected.inputStream().use { it.copyTo(result) }
                            else aar.getInputStream(entry).use { it.copyTo(result) }
                        }
                        result.closeEntry()
                    }
                }
            }
        } finally {
            original.delete()
            corrected.delete()
        }
    }

    @JvmStatic
    fun patchJar(input: File, output: File) {
        JarFile(input).use { jar ->
            val present = entries.filter { jar.getJarEntry(it) != null }
            check(present.size == 1) { "Expected one patcher SDK merger class in ${input.name}" }
            JarOutputStream(output.outputStream().buffered()).use { result ->
                for (entry in jar.entries()) {
                    // Rewriting a signed archive invalidates its signature metadata.
                    if (entry.name.matches(Regex("META-INF/[^/]+[.](SF|RSA|DSA|EC)", RegexOption.IGNORE_CASE))) continue
                    result.putNextEntry(JarEntry(entry.name).apply { time = 0L })
                    if (!entry.isDirectory) {
                        jar.getInputStream(entry).use { stream ->
                            if (entry.name in present) result.write(patchClass(stream.readBytes()))
                            else stream.copyTo(result)
                        }
                    }
                    result.closeEntry()
                }
            }
        }
    }
}
