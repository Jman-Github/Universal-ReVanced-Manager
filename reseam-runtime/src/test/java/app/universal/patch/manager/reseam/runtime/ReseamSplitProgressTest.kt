package app.universal.patch.manager.reseam.runtime

import org.junit.Assert.*
import org.junit.Test

class ReseamSplitProgressTest {
    @Test fun extractionAndComponentsRemainInOneExpandableSplitStep() {
        val events = mutableListOf<Map<String, Any?>>()
        val callback = object : ReseamRuntimeCallback {
            override fun event(event: Map<String, Any?>) { events += event }
            override fun log(level: String, message: String) = Unit
            override fun isCancelled() = false
        }
        val progress = ReseamSplitProgress(callback)
        progress.start()
        progress.extracting(0, 1024)
        progress.extracting(512, 1024)
        progress.extracting(1024, 1024)
        progress.inspecting()
        progress.components(listOf("base.apk", "config.en.apk"))
        progress.preparing("base.apk", false)
        progress.preparing("base.apk", true)
        progress.preparing("config.en.apk", false)
        assertFalse(events.any { it["type"] == "Completed" })
        progress.preparing("config.en.apk", true)
        progress.completed()

        assertTrue(events.all { it["stepId"] == mapOf("kind" to "PrepareSplitApk") })
        assertEquals("Started", events.first()["type"])
        assertEquals(listOf("Extracting split APKs", "Inspecting split APKs", "Preparing split APKs"),
            events.first()["subSteps"])
        val phases = events.last { it["subSteps"] != null }["subSteps"] as List<*>
        assertEquals(listOf("Extracting split APKs", "Inspecting split APKs",
            "Preparing base.apk", "Preparing config.en.apk"), phases)
        val copyEvents = events.filter { it["message"] == "Extracting split APKs" }
        assertEquals(listOf(0L, 512L, 1024L), copyEvents.map { it["current"] })
        assertTrue(copyEvents.all { it["total"] == 1024L })
        assertEquals("Completed", events.last()["type"])
    }
}
