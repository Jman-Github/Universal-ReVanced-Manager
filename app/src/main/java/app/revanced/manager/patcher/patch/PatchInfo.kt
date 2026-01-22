package app.revanced.manager.patcher.patch

import androidx.compose.runtime.Immutable
import app.revanced.patcher.patch.Patch
import app.revanced.patcher.patch.Option as RevancedPatchOption
import app.revanced.patcher.patch.resourcePatch as revancedResourcePatch
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet
import java.util.Locale
import kotlin.reflect.KType
import kotlin.reflect.typeOf

data class PatchInfo(
    val name: String,
    val description: String?,
    val include: Boolean,
    val compatiblePackages: ImmutableList<CompatiblePackage>?,
    val options: ImmutableList<Option<*>>?
) {
    constructor(patch: Patch<*>) : this(
        patch.name.orEmpty(),
        patch.description,
        patch.use,
        patch.compatiblePackages?.map { (pkgName, versions) ->
            CompatiblePackage(
                pkgName,
                versions?.toImmutableSet()
            )
        }?.toImmutableList(),
        patch.options.map { (_, option) -> Option(option) }.ifEmpty { null }?.toImmutableList()
    )

    fun compatibleWith(packageName: String) =
        compatiblePackages?.any { it.packageName == packageName } ?: true

    fun supports(packageName: String, versionName: String?): Boolean {
        val packages = compatiblePackages ?: return true // Universal patch

        return packages.any { pkg ->
            if (pkg.packageName != packageName) return@any false
            if (pkg.versions == null) return@any true

            versionName != null && versionName in pkg.versions
        }
    }

    /**
     * Create a fake ReVanced [Patch] with the same metadata as the [PatchInfo] instance.
     * The resulting patch cannot be executed.
     * This is necessary because some functions in ReVanced Library only accept full [Patch] objects.
     */
    fun toPatcherPatch(): Patch<*> =
        revancedResourcePatch(name = name, description = description, use = include) {
            compatiblePackages?.let { pkgs ->
                compatibleWith(*pkgs.map { it.packageName to it.versions }.toTypedArray())
            }
        }

    companion object {
        fun fromMorpheMetadata(metadata: Map<String, Any?>): PatchInfo {
            val name = (metadata["name"] as? String).orEmpty()
            val description = metadata["description"] as? String
            val include = metadata["use"] as? Boolean ?: true
            val compatiblePackages = (metadata["compatiblePackages"] as? List<*>)
                ?.mapNotNull { entry ->
                    val map = entry as? Map<*, *> ?: return@mapNotNull null
                    val packageName = map["packageName"] as? String ?: return@mapNotNull null
                    val versions = (map["versions"] as? Iterable<*>)
                        ?.mapNotNull { it as? String }
                        ?.toImmutableSet()
                        ?.takeIf { it.isNotEmpty() }
                    CompatiblePackage(packageName, versions)
                }
                ?.toImmutableList()
                ?.takeIf { it.isNotEmpty() }

            val options = (metadata["options"] as? List<*>)
                ?.mapNotNull { entry ->
                    val map = entry as? Map<*, *> ?: return@mapNotNull null
                    Option.fromMorpheMetadata(map)
                }
                ?.toImmutableList()
                ?.takeIf { it.isNotEmpty() }

            return PatchInfo(name, description, include, compatiblePackages, options)
        }
    }
}

@Immutable
data class CompatiblePackage(
    val packageName: String,
    val versions: ImmutableSet<String>?
)

@Immutable
data class Option<T>(
    val title: String,
    val key: String,
    val description: String,
    val required: Boolean,
    val type: KType,
    val default: T?,
    val presets: Map<String, T?>?,
    val validator: (T?) -> Boolean,
) {
    constructor(option: RevancedPatchOption<T>) : this(
        option.title ?: option.key,
        option.key,
        option.description.orEmpty(),
        option.required,
        option.type,
        option.default,
        option.values,
        { option.validator(option, it) },
    )

    companion object {
        private val scalarTypes = mapOf(
            "boolean" to typeOf<Boolean>(),
            "int" to typeOf<Int>(),
            "long" to typeOf<Long>(),
            "double" to typeOf<Float>(),
            "float" to typeOf<Float>(),
            "string" to typeOf<String>()
        )
        private val listTypes = mapOf(
            typeOf<Boolean>() to typeOf<List<Boolean>>(),
            typeOf<Int>() to typeOf<List<Int>>(),
            typeOf<Long>() to typeOf<List<Long>>(),
            typeOf<Float>() to typeOf<List<Float>>(),
            typeOf<String>() to typeOf<List<String>>()
        )

        fun fromMorpheMetadata(metadata: Map<*, *>): Option<Any> {
            val key = metadata["key"]?.toString().orEmpty()
            val title = metadata["title"]?.toString().orEmpty().ifBlank { key }
            val description = metadata["description"]?.toString().orEmpty()
            val required = metadata["required"] as? Boolean ?: false
            val type = parseType(metadata["type"])
            val default = coerceValue(type, metadata["default"])
            val presets = (metadata["presets"] as? Map<*, *>)?.mapNotNull { (name, value) ->
                val presetKey = name?.toString() ?: return@mapNotNull null
                val presetValue = coerceValue(type, value)
                presetKey to presetValue
            }?.toMap()?.takeIf { it.isNotEmpty() }

            return Option(
                title = title,
                key = key,
                description = description,
                required = required,
                type = type,
                default = default,
                presets = presets,
                validator = { true }
            )
        }

        private fun parseType(raw: Any?): KType {
            val normalized = raw?.toString()?.lowercase(Locale.US).orEmpty()
            val elementType = if (normalized.contains("list")) {
                val elementName = Regex("<(.+)>").find(normalized)?.groupValues?.getOrNull(1)
                elementName?.lowercase(Locale.US)
            } else null

            val resolvedScalar = resolveScalarType(elementType ?: normalized)
            if (normalized.contains("list")) {
                return listTypes[resolvedScalar] ?: typeOf<List<String>>()
            }

            return resolvedScalar
        }

        private fun resolveScalarType(raw: String): KType {
            val normalized = raw.lowercase(Locale.US)
            scalarTypes.forEach { (key, type) ->
                if (normalized.contains(key)) return type
            }
            return typeOf<String>()
        }

        private fun coerceValue(type: KType, value: Any?): Any? {
            if (value == null) return null
            if (type.classifier == List::class) {
                val elementType = type.arguments.first().type ?: typeOf<String>()
                val iterable = value as? Iterable<*> ?: return null
                val converted = mutableListOf<Any?>()
                for (element in iterable) {
                    if (element == null) return null
                    val coerced = coerceScalar(elementType, element) ?: return null
                    converted.add(coerced)
                }
                return converted
            }
            return coerceScalar(type, value)
        }

        private fun coerceScalar(type: KType, value: Any?): Any? {
            if (value == null) return null
            return when (type) {
                typeOf<Boolean>() -> when (value) {
                    is Boolean -> value
                    is String -> value.toBooleanStrictOrNull()
                    else -> null
                }
                typeOf<Int>() -> when (value) {
                    is Number -> value.toInt()
                    is String -> value.toIntOrNull()
                    else -> null
                }
                typeOf<Long>() -> when (value) {
                    is Number -> value.toLong()
                    is String -> value.toLongOrNull()
                    else -> null
                }
                typeOf<Float>() -> when (value) {
                    is Number -> value.toFloat()
                    is String -> value.toFloatOrNull()
                    else -> null
                }
                typeOf<String>() -> value.toString()
                else -> value.toString()
            }
        }
    }
}
