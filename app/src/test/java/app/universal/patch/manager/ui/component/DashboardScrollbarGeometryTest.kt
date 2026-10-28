package app.universal.patch.manager.ui.component

import org.junit.Assert.*
import org.junit.Test

class DashboardScrollbarGeometryTest {
    private fun geometry(count: Int) = lazyScrollbarGeometry(
        List(count) { 120 }, 0, 0, 12, 0, 12, 600, false, true, false
    )

    @Test fun dashboardThumbLengthIsIndependentOfContentLength() {
        assertEquals(64f, geometry(6).thumbSize(600f, 24f, 64f), 0f)
        assertEquals(64f, geometry(600).thumbSize(600f, 24f, 64f), 0f)
    }

    @Test fun thumbFitsShortViewports() {
        assertEquals(40f, geometry(6).thumbSize(40f, 24f, 64f), 0f)
    }

    @Test fun dragEndpointsStillReachBothEnds() {
        val model = geometry(20)
        assertEquals(0 to 0, model.target(0f))
        assertEquals(19 to 132, model.target(1f))
    }
}
