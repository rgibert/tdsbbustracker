package ca.bustracker.tstg.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ChildInfoPositionLinesTest {

    @Test
    fun splitsUpdatedAndReportedTimes() {
        val info = ChildInfo(lastPositionText = "Last updated 8:15 AM Last reported 8:14 AM")
        assertEquals(listOf("Last updated 8:15 AM", "Last reported 8:14 AM"), info.lastPositionLines)
    }

    @Test
    fun splitsWithSeparatorsAndColons() {
        val info = ChildInfo(lastPositionText = "Updated: 8:15:02 AM, Reported: 8:14:50 AM")
        assertEquals(listOf("Updated: 8:15:02 AM", "Reported: 8:14:50 AM"), info.lastPositionLines)
    }

    @Test
    fun keepsSingleTimeAndLeadingText() {
        assertEquals(listOf("Last updated 8:15 AM"), ChildInfo(lastPositionText = "Last updated 8:15 AM").lastPositionLines)
        assertEquals(listOf("Bus at 8:15 AM"), ChildInfo(lastPositionText = "Bus at 8:15 AM").lastPositionLines)
        assertEquals(emptyList<String>(), ChildInfo().lastPositionLines)
    }
}
