package ca.bustracker.tstg.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BusPlannerParserTest {

    @Test
    fun parsesLiveBusJsonEtaAndStudent() {
        val html = """
            <html><body>
              <h1 class="PageTitle"><span id="PageTitle">Where's My Bus?</span></h1>
              <select id="MainContent_ddlStudents">
                <option value="a">DOE, ALEX</option>
                <option selected="selected" value="b">DOE, SAM</option>
              </select>
              <span id="MainContent_NestContent_lblLatestPosition">Last updated 8:15 AM</span>
              <span id="MainContent_NestContent_lblMapDelays">On time</span>
              <input id="MainContent_NestContent_hfBusLocation"
                     value='[{"Latitude":43.5,"Longitude":-79.6,"Label":"RUN-1","ETA":"12"}]'>
              <input id="MainContent_NestContent_hfBusStopLocations"
                     value='[{"Latitude":43.4,"Longitude":-79.5}]'>
              <span id="lblBulletin" class="alert alert-danger">Vehicle information unavailable</span>
              <script>SetETAGauge('ETAChart', 12, 'Arriving in 12 min', 'a,b,c');</script>
            </body></html>
        """.trimIndent()

        val info = BusPlannerParser.parse(html, "https://tstg.mybusplanner.ca/Subscriptions/WheresMyBus")

        assertEquals("Sam Doe", info.studentName)
        assertEquals(12, info.etaMinutes)
        assertEquals("Arriving in 12 min", info.etaText)
        assertEquals(43.5, info.busLatitude!!, 0.0001)
        assertEquals(-79.6, info.busLongitude!!, 0.0001)
        assertEquals(43.4, info.stopLatitude!!, 0.0001)
        assertEquals(-79.5, info.stopLongitude!!, 0.0001)
        assertEquals("RUN-1", info.busLabel)
        assertEquals("Last updated 8:15 AM", info.lastPositionText)
        assertEquals("On time", info.mapDelayText)
        assertTrue(info.statusNote.orEmpty().contains("Vehicle information unavailable"))
        assertTrue(info.hasLiveBus)
    }

    @Test
    fun parsesChildTransportInfoLabelValuePairs() {
        val html = """
            <html><body>
              <table>
                <tr><th>Student</th><td>Sam Doe</td></tr>
                <tr><th>School</th><td>Example Elementary School</td></tr>
                <tr><th>Bus Number</th><td>1234</td></tr>
                <tr><th>Route</th><td>R-12</td></tr>
                <tr><th>Bus Stop</th><td>Main St &amp; 1st Ave</td></tr>
                <tr><th>AM Time</th><td>7:45 AM</td></tr>
                <tr><th>PM Time</th><td>3:30 PM</td></tr>
              </table>
            </body></html>
        """.trimIndent()

        val info = BusPlannerParser.parse(
            html,
            "https://tstg.mybusplanner.ca/Subscriptions/ChildTransportInfo",
        )

        assertEquals("Sam Doe", info.studentName)
        assertEquals("Example Elementary School", info.school)
        assertEquals("1234", info.busNumber)
        assertEquals("R-12", info.route)
        assertEquals("Main St & 1st Ave", info.stopName)
        assertEquals("7:45 AM", info.amTime)
        assertEquals("3:30 PM", info.pmTime)
        assertFalse(info.hasLiveBus)
    }

    @Test
    fun combinesStudentDetailsWithLiveData() {
        val childHtml = """
            <html><body>
              <table>
                <tr><th>Student</th><td>Sam Doe</td></tr>
                <tr><th>School</th><td>Example Elementary</td></tr>
                <tr><th>Bus Number</th><td>1234</td></tr>
                <tr><th>Bus Stop</th><td>Main St</td></tr>
              </table>
            </body></html>
        """.trimIndent()

        val liveHtml = """
            <html><body>
              <select id="MainContent_ddlStudents">
                <option selected="selected" value="b">DOE, SAM</option>
              </select>
              <label for="School">School</label>
              <select id="School"><option selected="selected" value="">--Select--</option></select>
              <span>12:00 AM</span>
              <input id="MainContent_NestContent_hfBusLocation"
                     value='[{"Latitude":43.5,"Longitude":-79.6}]'>
              <script>SetETAGauge('ETAChart', 7, '7 minutes', 'a,b,c');</script>
            </body></html>
        """.trimIndent()

        val info = BusPlannerParser.parsePages(
            listOf(
                PortalPage(childHtml, "https://tstg.mybusplanner.ca/Subscriptions/ChildTransportInfo"),
                PortalPage(liveHtml, "https://tstg.mybusplanner.ca/Subscriptions/WheresMyBus"),
            ),
        )

        assertEquals("Sam Doe", info.studentName)
        assertEquals("Example Elementary", info.school)
        assertEquals("1234", info.busNumber)
        assertEquals("Main St", info.stopName)
        assertNull(info.amTime)
        assertNull(info.pmTime)
        assertEquals(7, info.etaMinutes)
        assertTrue(info.hasLiveBus)
    }

    @Test
    fun unavailableVehicleDoesNotFakeLiveData() {
        val html = """
            <html><body>
              <h1 class="PageTitle"><span id="PageTitle">Where's My Bus?</span></h1>
              <input id="MainContent_NestContent_hfBusLocation" value="">
              <input id="MainContent_NestContent_hfBusStopLocations" value="">
              <span id="lblBulletin" class="alert alert-danger">
                Information about the vehicle associated to your child's transportation
                is unavailable at this time. Please try again later.
              </span>
            </body></html>
        """.trimIndent()

        val info = BusPlannerParser.parse(html)

        assertNull(info.busLatitude)
        assertNull(info.busLongitude)
        assertNull(info.etaMinutes)
        assertFalse(info.hasLiveBus)
        assertTrue(info.statusNote.orEmpty().contains("unavailable", ignoreCase = true))
    }

    @Test
    fun ignoresPageTitleAsStudentName() {
        val html = """
            <html><body>
              <h1 class="PageTitle"><span id="PageTitle">Where's My Bus?</span></h1>
            </body></html>
        """.trimIndent()

        val info = BusPlannerParser.parse(html)

        assertNull(info.studentName)
    }

    @Test
    fun detectsLoginPageAndNormalPage() {
        val login = """
            <html><body><form id="mainForm">
              <input id="MainContent_glogin_lLogin_UserName" name="user">
              <input type="password" id="Password">
              <div class="alert alert-danger">Your sign in attempt was not successful. Please try again.</div>
            </form></body></html>
        """.trimIndent()
        val normal = """
            <html><body><h1>Where's My Bus?</h1></body></html>
        """.trimIndent()

        assertTrue(BusPlannerParser.isLoginPage(login))
        assertFalse(BusPlannerParser.isLoginPage(normal))
        assertEquals(
            "Email or password is incorrect. Please try again.",
            BusPlannerParser.extractLoginError(login),
        )
    }

    // Layout of the real ChildTransportInfo "Transport" repeater (ids are the
    // portal's; data is made up). Each trip has a pick-up and a drop-off leg.
    private fun transportLeg(trip: Int, leg: Int, type: String, time: String, place: String) = """
        <span id="MainContent_NestContent_rpTransportation_rTransportation_${trip}_lblStopType_$leg">$type</span>
        <span id="MainContent_NestContent_rpTransportation_rTransportation_${trip}_lblArrivalValue_$leg">$time</span>
        <span id="MainContent_NestContent_rpTransportation_rTransportation_${trip}_lblDetailLocationValue_$leg">$place</span>
        <span id="MainContent_NestContent_rpTransportation_rTransportation_${trip}_RunRouteInfo_$leg">215</span>
    """

    private fun tripHeader(trip: Int, description: String) =
        """<span id="MainContent_NestContent_rpTransportation_lblTransportationDescription_$trip">$description</span>"""

    private val childUrl = "https://tstg.mybusplanner.ca/Subscriptions/ChildTransportInfo"

    @Test
    fun pmTimeIsAfternoonDropOffAtStopNotMorningDropOffAtSchool() {
        val html = """
            <html><body><form id="mainForm">
              <span id="MainContent_NestContent_lblSchoolValue">Maple Grove PS</span>
              ${tripHeader(0, "To School")}
              ${transportLeg(0, 0, "AM Pickup", "7:58 AM", "BIRCH AVE @ CEDAR RD")}
              ${transportLeg(0, 1, "AM Dropoff", "8:45 AM", "MAPLE GROVE PS - BUS LOOP")}
              ${tripHeader(1, "From School")}
              ${transportLeg(1, 0, "PM Pickup", "3:30 PM", "MAPLE GROVE PS - BUS LOOP")}
              ${transportLeg(1, 1, "PM Dropoff", "3:53 PM", "BIRCH AVE @ CEDAR RD")}
            </form></body></html>
        """.trimIndent()

        val info = BusPlannerParser.parse(html, childUrl)

        assertEquals("7:58 AM", info.amTime)
        assertEquals("3:53 PM", info.pmTime)
        assertEquals("BIRCH AVE @ CEDAR RD", info.stopName)
        assertEquals("215", info.route)
        assertEquals("Maple Grove PS", info.school)
    }

    @Test
    fun morningOnlyStudentHasNoPmTime() {
        val html = """
            <html><body><form id="mainForm">
              ${tripHeader(0, "To School")}
              ${transportLeg(0, 0, "AM Pickup", "7:36 AM", "BIRCH AVE @ CEDAR RD")}
              ${transportLeg(0, 1, "AM Dropoff", "8:00 AM", "MAPLE GROVE PS")}
            </form></body></html>
        """.trimIndent()

        val info = BusPlannerParser.parse(html, childUrl)

        assertEquals("7:36 AM", info.amTime)
        assertNull(info.pmTime)
    }

    @Test
    fun afternoonOnlyTripInFirstSlotIsStillAfternoon() {
        val html = """
            <html><body><form id="mainForm">
              ${tripHeader(0, "From School")}
              ${transportLeg(0, 0, "PM Pickup", "3:30 PM", "MAPLE GROVE PS")}
              ${transportLeg(0, 1, "PM Dropoff", "3:53 PM", "BIRCH AVE @ CEDAR RD")}
            </form></body></html>
        """.trimIndent()

        val info = BusPlannerParser.parse(html, childUrl)

        assertNull(info.amTime)
        assertEquals("3:53 PM", info.pmTime)
        assertEquals("BIRCH AVE @ CEDAR RD", info.stopName)
    }

    @Test
    fun textFallbackDoesNotUseMorningDropOffForPm() {
        val html = """
            <html><body>
              <table>
                <tr><th>AM Pickup</th><td>7:58 AM</td></tr>
                <tr><th>AM Dropoff</th><td>8:45 AM</td></tr>
                <tr><th>PM Pickup</th><td>3:30 PM</td></tr>
                <tr><th>PM Dropoff</th><td>3:53 PM</td></tr>
              </table>
            </body></html>
        """.trimIndent()

        val info = BusPlannerParser.parse(html, childUrl)

        assertEquals("7:58 AM", info.amTime)
        assertEquals("3:53 PM", info.pmTime)
    }

    private val studentPicker = """
        <form method="post" action="./ChildTransportInfo" id="mainForm">
          <input type="hidden" name="__VIEWSTATE" value="vs123">
          <input type="hidden" name="__EVENTVALIDATION" value="ev456">
          <select name="ctl00${'$'}ddlLanguages"><option selected value="en-CA">English</option></select>
          <div id="MainContent_divStudentSelector">
            <select name="ctl00${'$'}MainContent${'$'}ddlStudents" id="MainContent_ddlStudents"
                    onchange="javascript:setTimeout('__doPostBack(\'ctl00${'$'}MainContent${'$'}ddlStudents\',\'\')', 0)">
              <option selected="selected" value="111">DOE, ALEX</option>
              <option value="222">DOE, SAM</option>
            </select>
          </div>
          <input type="text" name="ctl00${'$'}MainContent${'$'}txtNewStudent" value="">
        </form>
    """.trimIndent()

    @Test
    fun listsStudentsFromPortalDropdown() {
        val info = BusPlannerParser.parse("<html><body>$studentPicker</body></html>", childUrl)

        assertEquals(
            listOf(
                StudentOption("111", "Alex Doe", selected = true),
                StudentOption("222", "Sam Doe", selected = false),
            ),
            info.students,
        )
        assertEquals("111", info.selectedStudentId)
        assertEquals("Alex Doe", info.studentName)
    }

    @Test
    fun buildsStudentSwitchPostback() {
        val html = "<html><body>$studentPicker</body></html>"

        val form = BusPlannerParser.studentSwitchForm(html, childUrl, "222")

        assertNotNull(form)
        form!!
        assertEquals(childUrl, form.url)
        assertEquals("ctl00\$MainContent\$ddlStudents", form.fields["__EVENTTARGET"])
        assertEquals("222", form.fields["ctl00\$MainContent\$ddlStudents"])
        assertEquals("vs123", form.fields["__VIEWSTATE"])
        assertEquals("ev456", form.fields["__EVENTVALIDATION"])
        assertEquals("en-CA", form.fields["ctl00\$ddlLanguages"])
        assertFalse(form.fields.containsKey("ctl00\$MainContent\$txtNewStudent"))

        // Already showing that student, or an unknown id: nothing to post.
        assertNull(BusPlannerParser.studentSwitchForm(html, childUrl, "111"))
        assertNull(BusPlannerParser.studentSwitchForm(html, childUrl, "999"))
    }

    @Test
    fun readsSelectedStudentWhenNotFirst() {
        val child = PortalPage(
            "<html><body>$studentPicker</body></html>".replace(
                "selected=\"selected\" value=\"111\"",
                "value=\"111\"",
            ).replace("<option value=\"222\">", "<option selected value=\"222\">"),
            childUrl,
        )
        val info = BusPlannerParser.parsePages(listOf(child))

        assertEquals("222", info.selectedStudentId)
        assertEquals("Sam Doe", info.studentName)
    }
}
