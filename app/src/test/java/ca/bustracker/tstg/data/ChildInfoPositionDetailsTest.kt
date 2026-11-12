package ca.bustracker.tstg.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChildInfoPositionDetailsTest {

    private fun details(text: String?) = ChildInfo(lastPositionText = text).positionDetails

    @Test
    fun stripsTrailingLatestAndSplitsOffTheRun() {
        val d = details("Last updated 8:15 AM latest Last reported 8:14 AM Run 12A")
        assertEquals(
            listOf(PositionTime("Reported", "8:14 AM")),
            d.times,
        )
        assertEquals("Run 12A", d.run)
        assertEquals(emptyList<String>(), d.other)
    }

    @Test
    fun handlesLatestAsPrefixAndColons() {
        val d = details("Updated: 8:15:02 AM, Latest reported: 8:14:50 AM, Run: R-7")
        assertEquals(
            listOf(PositionTime("Reported", "8:14:50 AM")),
            d.times,
        )
        assertEquals("Run: R-7", d.run)
    }

    @Test
    fun singleTimeAndUnrecognisedText() {
        assertEquals(emptyList<PositionTime>(), details("Last updated 8:15 AM").times)
        assertEquals(
            listOf(PositionTime("Reported time", "8:14 AM")),
            details("Last reported time: 8:14 AM").times,
        )
        val d = details("No position available")
        assertEquals(emptyList<PositionTime>(), d.times)
        assertEquals(listOf("No position available"), d.other)
        assertNull(details(null).run)
    }
}
