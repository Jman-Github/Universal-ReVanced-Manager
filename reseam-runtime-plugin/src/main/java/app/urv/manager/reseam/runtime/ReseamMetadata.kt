package app.urv.manager.reseam.runtime

import app.reseam.sdk.Compatibility
import app.reseam.sdk.OptionDeclaration
import app.reseam.sdk.OptionType
import app.reseam.sdk.OptionValue
import app.reseam.sdk.PatchSpec

// Adapted to URV's runtime metadata contract from Reseam's public SDK model.
// https://git.reseam.app/reseam/reseam/src/tag/v0.18.3/crates/model/src/patch.rs
internal object ReseamMetadata {
    /** URV selections use names; qualify them because Reseam permits duplicate display names. */
    fun label(patch: PatchSpec): String = "${patch.name} [${patch.id}]"

    fun toMap(patch: PatchSpec): Map<String, Any?> = mapOf(
        "name" to label(patch),
        "displayName" to patch.name,
        "description" to patch.description,
        "use" to patch.enabledByDefault,
        "compatiblePackages" to when (val compatibility = patch.compatibility) {
            Compatibility.Universal -> null
            is Compatibility.Packages -> compatibility.packages.map {
                mapOf("packageName" to it.`package`, "versions" to it.versions.ifEmpty { null })
            }
        },
        "options" to patch.options.map(::optionToMap)
    )

    private fun optionToMap(option: OptionDeclaration): Map<String, Any?> = mapOf(
        "key" to option.key,
        "title" to option.title,
        "description" to option.description,
        "required" to option.required,
        "type" to when (option.optionType) {
            OptionType.STRING, OptionType.PATH -> "kotlin.String"
            OptionType.BOOL -> "kotlin.Boolean"
            OptionType.INT -> "kotlin.Long"
            OptionType.FLOAT -> "kotlin.Double"
            OptionType.STRING_LIST -> "kotlin.collections.List<kotlin.String>"
        },
        "default" to option.defaultValue?.let(::value),
        "presets" to option.validValues?.associateWith {
            if (option.optionType == OptionType.STRING_LIST) listOf(it) else it
        },
        "explicitKind" to if (option.optionType == OptionType.PATH) "FilePath" else null
    )

    fun value(value: OptionValue): Any = when (value) {
        is OptionValue.Text -> value.field0
        is OptionValue.Path -> value.field0
        is OptionValue.Bool -> value.field0
        is OptionValue.Int -> value.field0
        is OptionValue.Float -> value.field0
        is OptionValue.TextList -> value.field0
    }

    fun optionValue(option: OptionDeclaration, value: Any): OptionValue =
        when (option.optionType) {
            OptionType.STRING -> OptionValue.Text(value as String)
            OptionType.PATH -> OptionValue.Path(value as String)
            OptionType.BOOL -> OptionValue.Bool(value as Boolean)
            OptionType.INT -> OptionValue.Int((value as Number).toLong())
            OptionType.FLOAT -> OptionValue.Float((value as Number).toDouble())
            OptionType.STRING_LIST -> OptionValue.TextList((value as List<*>).map { it as String })
        }
}
