package app.universal.patch.manager.patcher.runtime.revanced

import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/** Registry v21 includes the class/annotation API that predates the v20 patch DSL. */
object RevancedLegacyFormat {
    private val markers = listOf(
        "app/revanced/patcher/fingerprint/MethodFingerprint",
        "app/revanced/patcher/patch/annotation/Patch",
        "app/revanced/patcher/annotation/Patch"
    )
    private val overlap = markers.maxOf { it.length }
    private val legacySuperclasses = setOf(
        "app/revanced/patcher/patch/ResourcePatch",
        "app/revanced/patcher/patch/BytecodePatch"
    )

    // A class-based patch may supply metadata in its constructor instead of annotations.
    private fun hasLegacySuperclass(input: DataInputStream): Boolean {
        if (input.readInt() != 0xCAFEBABE.toInt()) return false
        input.readUnsignedShort()
        input.readUnsignedShort()
        val pool = arrayOfNulls<Any>(input.readUnsignedShort())
        var index = 1
        while (index < pool.size) {
            when (val tag = input.readUnsignedByte()) {
                1 -> pool[index] = input.readUTF()
                7 -> pool[index] = input.readUnsignedShort()
                3, 4, 9, 10, 11, 12, 17, 18 -> input.readInt()
                5, 6 -> { input.readLong(); index++ }
                8, 16, 19, 20 -> input.readUnsignedShort()
                15 -> { input.readUnsignedByte(); input.readUnsignedShort() }
                else -> error("Invalid class constant pool tag: $tag")
            }
            index++
        }
        input.readUnsignedShort()
        input.readUnsignedShort()
        val superclass = pool.getOrNull(input.readUnsignedShort()) as? Int ?: return false
        return pool.getOrNull(superclass) in legacySuperclasses
    }

    fun isLegacyBundle(file: File): Boolean = ZipFile(file).use { zip ->
        zip.entries().asSequence().filter {
            !it.isDirectory && (it.name.endsWith(".dex") || it.name.endsWith(".class"))
        }.any { entry ->
            zip.getInputStream(entry).use(::hasLegacyApi) ||
                zip.getInputStream(entry).buffered().use { input ->
                    if (entry.name.endsWith(".dex")) {
                        DexBackedDexFile.fromInputStream(null, input).classes.any {
                            it.superclass?.removePrefix("L")?.removeSuffix(";") in legacySuperclasses
                        }
                    } else hasLegacySuperclass(DataInputStream(input))
                }
        }
    }

    fun selectionUsesLegacy(paths: Collection<String>): Boolean {
        val modes = paths.map { isLegacyBundle(File(it)) }.toSet()
        require(modes.size <= 1) {
            "Legacy class-based ReVanced patches and newer DSL patches cannot be combined in one run."
        }
        return modes.singleOrNull() == true
    }

    private fun hasLegacyApi(input: InputStream): Boolean {
        val buffer = ByteArray(8192)
        var tail = ""
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return false
            val text = tail + String(buffer, 0, count, Charsets.ISO_8859_1)
            if (markers.any(text::contains)) return true
            tail = text.takeLast(overlap)
        }
    }
}
