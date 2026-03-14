package app.revanced.manager.patcher.split

import android.util.Log
import com.reandroid.apk.ApkModule
import com.reandroid.app.AndroidManifest
import com.reandroid.archive.ZipEntryMap
import com.reandroid.arsc.chunk.xml.ResXmlElement
import com.reandroid.arsc.container.SpecTypePair
import com.reandroid.arsc.model.ResourceEntry
import com.reandroid.arsc.value.ValueType

internal object SplitManifestCleaner {
    @JvmStatic
    fun clean(module: ApkModule) {
        val manifest = module.androidManifest
        manifest.apply {
            arrayOf(
                AndroidManifest.ID_isSplitRequired,
                AndroidManifest.ID_requiredSplitTypes,
                AndroidManifest.ID_splitTypes
            ).forEach { id ->
                applicationElement.removeAttributesWithId(id)
                manifestElement.removeAttributesWithId(id)
            }

            arrayOf(
                AndroidManifest.NAME_requiredSplitTypes,
                AndroidManifest.NAME_splitTypes
            ).forEach { attrName ->
                manifestElement.removeAttributeIf { attribute ->
                    attribute.name == attrName
                }
            }

            // Remove split requirements so the merged APK installs as a single package.
            manifestElement.removeElementsIf { element ->
                element.name == "uses-split"
            }
            arrayOf("splitName", "split").forEach { attrName ->
                manifestElement.removeAttributeIf { attribute ->
                    attribute.name == attrName
                }
                applicationElement.removeAttributeIf { attribute ->
                    attribute.name == attrName
                }
            }

            applicationElement.removeElementsIf { element ->
                if (element.name != AndroidManifest.TAG_meta_data) return@removeElementsIf false
                val nameAttr = element
                    .getAttributes { it.nameId == AndroidManifest.ID_name }
                    .asSequence()
                    .singleOrNull()
                    ?: return@removeElementsIf false
                val nameValue = nameAttr.valueString ?: return@removeElementsIf false
                val shouldRemove = when {
                    nameValue == "com.android.dynamic.apk.fused.modules" -> {
                        val valueAttr = element
                            .getAttributes { it.nameId == AndroidManifest.ID_value }
                            .asSequence()
                            .firstOrNull()
                        valueAttr?.valueString == "base"
                    }
                    nameValue.startsWith("com.android.vending.") -> true
                    nameValue.startsWith("com.android.stamp.") -> true
                    else -> false
                }
                if (!shouldRemove) return@removeElementsIf false
                removeSplitMetaResources(module, element, nameValue)
                true
            }

            refresh()
        }
        module.refreshTable()
        module.refreshManifest()
    }

    private fun removeSplitMetaResources(
        module: ApkModule,
        element: ResXmlElement,
        nameValue: String
    ) {
        if (nameValue != "com.android.vending.splits") return
        if (!module.hasTableBlock()) return
        val valueAttr = element
            .getAttributes {
                it.nameId == AndroidManifest.ID_value || it.nameId == AndroidManifest.ID_resource
            }
            .asSequence()
            .firstOrNull()
            ?: return
        if (valueAttr.valueType != ValueType.REFERENCE) return

        val table = module.tableBlock
        val resourceEntry = table.getResource(valueAttr.data) ?: return
        val zipEntryMap = module.zipEntryMap
        removeResourceEntryFiles(resourceEntry, zipEntryMap)
        table.refresh()
    }

    private fun removeResourceEntryFiles(
        resourceEntry: ResourceEntry,
        zipEntryMap: ZipEntryMap
    ) {
        for (entry in resourceEntry) {
            val resEntry = entry ?: continue
            val resValue = resEntry.resValue ?: continue
            val path = resValue.valueAsString
            if (!path.isNullOrBlank()) {
                zipEntryMap.remove(path)
                Log.i("APKEditor", "Removed table entry $path")
            }
            resEntry.setNull(true)
            val specTypePair: SpecTypePair = resEntry.typeBlock.parentSpecTypePair
            specTypePair.removeNullEntries(resEntry.id)
        }
    }
}
