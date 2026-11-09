package ca.bustracker.tstg.data

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.math.roundToInt

/**
 * Parser for the TSTG BusPlanner Web parent portal.
 *
 * There are two useful pages:
 *   - /Subscriptions/ChildTransportInfo: school, bus, route, stop, AM/PM times
 *   - /Subscriptions/WheresMyBus: live GPS position, ETA gauge, map delays
 *
 * The live page keeps its data in hidden inputs:
 *   #MainContent_NestContent_hfBusLocation      JSON array of bus positions
 *   #MainContent_NestContent_hfBusStopLocations JSON array of stop positions
 *
 * The ETA is rendered by a SetETAGauge(...) inline script. Both strategies are
 * parsed here, with label/value text as a fallback for older layouts.
 *
 * ChildTransportInfo lists each trip in an ASP.NET repeater:
 *   ..._rpTransportation_lblTransportationDescription_<trip>   "To School"/"From School"
 *   ..._rTransportation_<trip>_lblStopType_<leg>               "AM Pickup", "PM Dropoff"…
 *   ..._rTransportation_<trip>_lblArrivalValue_<leg>           time
 *   ..._rTransportation_<trip>_lblDetailLocationValue_<leg>    stop or school
 *   ..._rTransportation_<trip>_RunRouteInfo_<leg>              route
 * Each trip has a pick-up and a drop-off, so "drop off" alone is ambiguous:
 * the morning drop-off is at the school. We read the trips explicitly.
 */
object BusPlannerParser {

    private val genericNames = setOf(
        "my students", "students", "student", "student information",
        "transportation information", "student transportation", "my student",
        "parent portal", "bus planner", "home", "log in", "alerts",
        "my account", "my subscriptions", "where's my bus", "where's my bus?",
        "wheres my bus", "wheres my bus?", "select student", "welcome",
    )

    private val etaGaugeRegex = Regex(
        """SetETAGauge\(\s*['"][^'"]*['"]\s*,\s*(-?\d+(?:\.\d+)?)\s*,\s*['"]([^'"]*)['"]""",
        RegexOption.IGNORE_CASE,
    )

    private val addMarkerRegex = Regex(
        """addMarker\(\s*['"]?(-?\d{1,3}(?:\.\d+)?)['"]?\s*,\s*['"]?(-?\d{1,3}(?:\.\d+)?)['"]?\s*,\s*['"]([^'"]+)['"]""",
        RegexOption.IGNORE_CASE,
    )

    private val busRegex = Regex(
        """(?i)\b(?:bus|vehicle)\b(?!\s*stop)\s*(?:number|no\.?|#)?\s*[:\-]?\s*([A-Z]{0,3}\d{1,6}[A-Z]?)\b""",
    )

    private val routeRegex = Regex(
        """(?i)\b(?:route|run)\b(?!\s*map)\s*(?:number|no\.?|#)?\s*[:\-]?\s*([A-Z0-9][A-Z0-9\-]{0,11})\b""",
    )

    private val stopRegex = Regex(
        """(?i)\b(?:bus\s+stop|stop(?:\s+name)?)\b\s*[:\-]\s*([^\n\r|•]{2,80})""",
    )

    private val etaTextRegex = Regex(
        """(?i)\b(?:ETA|estimated\s+(?:time\s+of\s+)?arrival|arriving(?:\s+in)?|arrival(?:\s+in)?)\b[^\d\-]{0,24}(-?\d{1,3})\s*(?:min|minutes?|mins?)""",
    )

    private val timeWithMeridiem = Regex(
        """\b(\d{1,2}:\d{2}\s*(?:AM|PM))\b""",
        RegexOption.IGNORE_CASE,
    )

    private val bareTime = Regex("""\b(\d{1,2}:\d{2})\b""")

    fun parse(html: String, pageUrl: String = ""): ChildInfo {
        if (html.isBlank()) return ChildInfo()
        val doc = Jsoup.parse(html, pageUrl)
        val body = doc.body() ?: return ChildInfo()
        val pairs = collectPairs(doc)

        // Hidden form widgets (date/time pickers, select2 boxes) inject noise
        // like "12:00 AM" into body text. Keep them out of the regex fallbacks.
        val textDoc = doc.clone()
        textDoc.select(
            "script, style, noscript, .xdsoft_datetimepicker, " +
                ".select2-hidden-accessible, [aria-hidden=true]",
        ).remove()
        val text = textDoc.body()?.text() ?: body.text()
        val scripts = doc.select("script").joinToString("\n") { it.data() }

        // The Where's My Bus page also contains the "Add Student" modal, whose
        // school dropdown and blank form fields look like real student data.
        // Only trust the static-detail fields on the ChildTransportInfo page.
        val livePage = pageUrl.contains("WheresMyBus", ignoreCase = true)

        val live = findLiveBus(doc)
        val gauge = findEtaGauge(scripts)

        val students = findStudents(doc)
        val selectedStudent = students.firstOrNull { it.selected } ?: students.firstOrNull()
        val studentName = selectedStudent?.name ?: findStudentName(doc, pairs)

        val trips = if (livePage) emptyList() else findTrips(doc)
        val amPickup = trips.firstOrNull { it.afternoon == false }?.pickup
        val pmDropoff = trips.firstOrNull { it.afternoon == true }?.dropoff
        val tripRoute = trips.asSequence()
            .flatMap { sequenceOf(it.pickup?.route, it.dropoff?.route) }
            .firstOrNull { !it.isNullOrBlank() }

        val busNumber = if (livePage) null else pickBus(pairs) ?: firstMatch(busRegex, text)
        val route = if (livePage) {
            null
        } else {
            tripRoute ?: pickRoute(pairs) ?: firstMatch(routeRegex, text)
        }
        // The student's own stop: where they board in the morning, or where
        // they get off in the afternoon. Never the school end of a trip.
        val stopName = if (livePage) {
            null
        } else {
            amPickup?.location ?: pmDropoff?.location
                ?: pickStop(pairs) ?: firstMatch(stopRegex, text)
        }
        val stopAddress = if (livePage) {
            null
        } else {
            pick(pairs, "stop address", "bus stop address", "address")
        }

        val amTime = if (livePage || trips.isNotEmpty()) {
            amPickup?.time
        } else {
            pick(
                pairs,
                "am pickup time", "am pick up time", "am pickup", "am pick up",
                "am time", "am", "morning time", "to school time", "to school",
                "pick up time", "pickup time", "pick up", "pickup",
                reject = { label -> label.startsWith("pm") || label.contains("drop") },
            ) ?: findTimeNear(text, "am pickup", "am pick up", "morning", "to school")
        }
        val pmTime = if (livePage || trips.isNotEmpty()) {
            pmDropoff?.time
        } else {
            // Not plain "drop off": the first one on the page is the AM
            // drop-off at the school.
            pick(
                pairs,
                "pm dropoff time", "pm drop off time", "pm dropoff", "pm drop off",
                "pm time", "pm", "afternoon time", "from school time", "from school",
                reject = { label -> label.startsWith("am") || label.contains("pick") },
            ) ?: findTimeNear(text, "pm dropoff", "pm drop off", "afternoon", "from school")
        }

        val lastPositionText = textOf(doc, "#MainContent_NestContent_lblLatestPosition")
        val mapDelayText = textOf(doc, "#MainContent_NestContent_lblMapDelays")

        val etaMinutes = live?.etaMinutes
            ?: gauge?.minutes
            ?: firstMatch(etaTextRegex, text)?.toIntOrNull()
        val etaText = gauge?.title?.takeIf { it.isNotBlank() }
            ?: live?.etaText?.takeIf { it.isNotBlank() }
            ?: firstMatch(etaTextRegex, text)?.let { "$it min" }

        val busMarker = live?.position ?: findBusMarker(html)
        val stopMarker = live?.stopPosition ?: findStopMarker(html)

        return ChildInfo(
            studentName = studentName,
            school = if (livePage) {
                null
            } else {
                textOf(doc, "[id\$=lblSchoolValue]") ?: pick(pairs, "school", "school name")
            },
            students = students,
            selectedStudentId = selectedStudent?.id,
            busNumber = busNumber?.cleanValue(),
            route = route?.cleanValue(),
            stopName = stopName?.cleanValue(),
            stopAddress = stopAddress?.cleanValue(),
            amTime = amTime?.cleanValue(),
            pmTime = pmTime?.cleanValue(),
            etaMinutes = etaMinutes,
            etaText = etaText?.cleanValue(),
            statusNote = findStatusNote(doc),
            busLatitude = busMarker?.first,
            busLongitude = busMarker?.second,
            busLabel = live?.label?.cleanValue()?.takeIf { it.isNotBlank() },
            stopLatitude = stopMarker?.first,
            stopLongitude = stopMarker?.second,
            lastPositionText = lastPositionText,
            mapDelayText = mapDelayText,
        )
    }

    /**
     * Parses several portal pages and combines them. Later pages win for
     * live data, so pass ChildTransportInfo first and Where's My Bus last.
     */
    fun parsePages(pages: List<PortalPage>): ChildInfo {
        if (pages.isEmpty()) return ChildInfo()
        return pages
            .map { parse(it.html, it.url) }
            .reduce { combined, next -> next.withFallback(combined) }
    }

    /**
     * True when the response is the portal login form. A session can expire
     * even while the app is open, so this check is used by both the native
     * client and the WebView.
     */
    fun isLoginPage(html: String): Boolean {
        if (html.isBlank()) return true
        if (html.contains("Your sign in attempt was not successful", ignoreCase = true)) {
            return true
        }
        val doc = Jsoup.parse(html)
        return doc.selectFirst("#MainContent_glogin_lLogin_UserName") != null ||
            doc.selectFirst("input[name=ctl00\$MainContent\$glogin\$lLogin\$Password]") != null ||
            doc.selectFirst("form#mainForm #Password") != null
    }

    fun extractLoginError(html: String): String? {
        val doc = Jsoup.parse(html)
        val selectors = listOf(
            "#UpdatePanel_Bulletin",
            "#MainContent_glogin_lLogin_LoginUserValidationSummary",
            ".validation-summary-errors",
            ".alert-danger",
            ".error",
        )
        val message = selectors.asSequence()
            .flatMap { doc.select(it).asSequence() }
            .map { it.text().cleanValue() }
            .firstOrNull { it.isNotBlank() && it.length in 4..240 }
            ?: return null

        val lower = message.lowercase()
        return when {
            "not successful" in lower || "invalid" in lower ->
                "Email or password is incorrect. Please try again."

            "locked" in lower ->
                "This account appears to be locked. Reset your password or contact TSTG."

            "confirm" in lower || "validate" in lower ->
                "This account has not been confirmed yet. Check your email for the activation link."

            "captcha" in lower || "robot" in lower ->
                "The portal is asking for extra verification. Try signing in through the browser once."

            else -> message
        }
    }

    private fun collectPairs(doc: Document): Map<String, String> {
        val pairs = linkedMapOf<String, String>()

        fun add(label: String, value: String) {
            val key = normalizeKey(label)
            val clean = value.cleanValue()
            if (key.isNotBlank() && clean.isNotBlank() && !clean.isPlaceholder()) {
                pairs.putIfAbsent(key, clean)
            }
        }

        // Table rows: pair adjacent cells (label, value).
        doc.select("tr").forEach { row ->
            val cells = row.children().filter { it.tagName() == "th" || it.tagName() == "td" }
            var i = 0
            while (i + 1 < cells.size) {
                add(cells[i].text(), cells[i + 1].text())
                i += 2
            }
            // Some layouts use a single wide header/description row.
            if (cells.size == 3) {
                add(cells[0].text(), cells[1].text())
                add(cells[1].text(), cells[2].text())
            }
        }

        // Definition lists.
        doc.select("dt").forEach { dt ->
            val dd = dt.nextElementSibling()
            if (dd != null && dd.tagName() == "dd") add(dt.text(), dd.text())
        }

        // Label-ish elements followed by their value.
        doc.select(
            "label, [class*=label], [class*=Label], [class*=field-name], [class*=fieldName], [data-label]",
        ).forEach { el ->
            val label = el.attr("data-label").ifBlank { el.text() }
            if (label.isBlank()) return@forEach

            val forId = el.attr("for")
            val value = if (forId.isNotBlank()) {
                doc.getElementById(forId)?.let { target ->
                    if (target.tagName() == "select") {
                        target.selectFirst("option[selected]")?.text()
                            ?: target.selectFirst("option")?.text()
                    } else {
                        target.attr("value").ifBlank { target.text() }
                    }
                }
            } else {
                null
            } ?: el.nextElementSibling()?.text()
                ?: el.parent()?.nextElementSibling()?.text()

            if (value != null && !value.contains(el.text())) {
                add(label, value)
            }
        }

        return pairs
    }

    private fun studentSelect(doc: Document): Element? =
        doc.selectFirst("#MainContent_ddlStudents")
            ?: doc.selectFirst("select[id*=ddlStudents]")
            ?: doc.selectFirst("select[name*=ddlStudents]")

    private fun findStudents(doc: Document): List<StudentOption> {
        val select = studentSelect(doc) ?: return emptyList()
        val options = select.select("option").mapNotNull { option ->
            val id = option.attr("value").trim()
            val name = formatStudentName(option.text())
            if (id.isBlank() || name == null) {
                null
            } else {
                StudentOption(id = id, name = name, selected = option.hasAttr("selected"))
            }
        }
        // A page with no explicit selection shows the first student.
        return if (options.any { it.selected }) {
            options
        } else {
            options.mapIndexed { index, option -> option.copy(selected = index == 0) }
        }
    }

    /**
     * Builds the ASP.NET postback that the portal's own "Select student"
     * dropdown sends when it changes. Returns null when the page has no
     * student dropdown, [studentId] is not one of its options, or it is
     * already selected (no request needed).
     */
    fun studentSwitchForm(html: String, pageUrl: String, studentId: String): PostbackForm? {
        val doc = Jsoup.parse(html, pageUrl)
        val select = studentSelect(doc) ?: return null
        val name = select.attr("name").ifBlank { return null }
        val options = findStudents(doc)
        if (options.none { it.id == studentId }) return null
        if (options.firstOrNull { it.selected }?.id == studentId) return null

        val form = select.closest("form") ?: doc.selectFirst("form#mainForm") ?: return null
        val fields = linkedMapOf<String, String>()
        form.select("input[type=hidden]").forEach { input ->
            val field = input.attr("name")
            if (field.isNotBlank()) fields[field] = input.attr("value")
        }
        // Keep the page language as it is.
        form.select("select[name\$=ddlLanguages]").forEach { languages ->
            val value = languages.selectFirst("option[selected]")?.attr("value")
                ?: languages.selectFirst("option")?.attr("value")
            if (value != null) fields[languages.attr("name")] = value
        }
        fields["__EVENTTARGET"] = name
        fields["__EVENTARGUMENT"] = ""
        fields["__LASTFOCUS"] = ""
        fields[name] = studentId

        val action = form.absUrl("action").ifBlank { pageUrl }
        return PostbackForm(url = action, fields = fields)
    }

    /**
     * Reads the "To School" / "From School" trips. A trip counts as the
     * afternoon one when its description or stop types say so; otherwise
     * repeater order (morning first) decides.
     */
    private fun findTrips(doc: Document): List<Trip> {
        val trips = mutableListOf<Trip>()
        for (index in 0 until 6) {
            val prefix = "rTransportation_${index}_"
            val legs = (0 until 6).mapNotNull { legIndex ->
                val type = textOf(doc, "[id\$=${prefix}lblStopType_$legIndex]")
                val time = textOf(doc, "[id\$=${prefix}lblArrivalValue_$legIndex]")
                val location = textOf(doc, "[id\$=${prefix}lblDetailLocationValue_$legIndex]")
                val route = textOf(doc, "[id\$=${prefix}RunRouteInfo_$legIndex]")
                if (type == null && time == null && location == null && route == null) {
                    null
                } else {
                    Leg(legIndex, type, time?.normalizeTime(), location, route)
                }
            }
            if (legs.isEmpty()) continue

            val description = textOf(
                doc,
                "[id\$=rpTransportation_lblTransportationDescription_$index]",
            ).orEmpty().lowercase()
            val types = legs.mapNotNull { it.type?.lowercase() }
            val afternoon = when {
                types.any { it.startsWith("pm") } -> true
                types.any { it.startsWith("am") } -> false
                "from" in description || description.startsWith("de ") -> true
                "to " in description || description.startsWith("à ") ||
                    description.startsWith("a ") -> false
                else -> null
            }

            fun Leg.isDropoff() = type?.lowercase()?.let {
                "drop" in it || "débarq" in it || "debarq" in it || "dépos" in it
            }
            fun Leg.isPickup() = type?.lowercase()?.let {
                "pick" in it || "embarq" in it || "ramass" in it
            }
            val pickup = legs.firstOrNull { it.isPickup() == true }
                ?: legs.firstOrNull { it.isDropoff() != true && it.index == 0 }
            val dropoff = legs.firstOrNull { it.isDropoff() == true }
                ?: legs.lastOrNull { it.isPickup() != true && it !== pickup }
            trips += Trip(afternoon, pickup, dropoff)
        }

        // Fill in unknown directions by order: morning, then afternoon.
        if (trips.any { it.afternoon == null }) {
            val hasMorning = trips.any { it.afternoon == false }
            return trips.mapIndexed { index, trip ->
                if (trip.afternoon != null) {
                    trip
                } else {
                    trip.copy(afternoon = if (hasMorning) true else index > 0)
                }
            }
        }
        return trips
    }

    private fun findStudentName(doc: Document, pairs: Map<String, String>): String? {
        val selectors = listOf(
            ".child_info h1, .child_info h2, .child_info h3, .child_info h4",
            ".student-name, .studentName, [class*=student-name], [class*=studentName]",
            "#accordion .info_header, .info_header",
            "h1, h2, h3, h4",
        )

        for (selector in selectors) {
            for (element in doc.select(selector)) {
                if (element.id() == "PageTitle") continue
                val candidate = cleanStudentName(element.text())
                if (candidate != null) return candidate
            }
        }

        val labelValue = pick(
            pairs,
            "student", "student name", "name",
            reject = { label -> label.contains("select") || label.contains("school") },
        )
        return labelValue?.let { cleanStudentName(it) }
    }

    private fun formatStudentName(raw: String): String? {
        val value = raw.cleanValue()
        if (value.length !in 2..70) return null
        if (value.startsWith("--")) return null
        if (value.equals("select student", ignoreCase = true)) return null

        // BusPlanner lists students as "LAST, FIRST". Make that friendlier.
        val comma = value.indexOf(',')
        if (comma > 0 && comma < value.lastIndex) {
            val last = value.substring(0, comma).titleCaseWords()
            val first = value.substring(comma + 1).titleCaseWords()
            if (first.isNotBlank() && last.isNotBlank()) return "$first $last"
        }
        return value
    }

    private fun cleanStudentName(raw: String): String? {
        var value = raw.cleanValue()
        if (value.length !in 2..70) return null
        if (value.endsWith("?")) return null
        if (genericNames.any { value.equals(it, ignoreCase = true) }) return null
        if (genericNames.any { value.startsWith("$it ", ignoreCase = true) }) return null

        value = value.substringBefore(" - ").substringBefore(" (").trim()
        val lower = value.lowercase()
        if (lower.contains("transport") || lower.contains("information") ||
            lower.contains("school year") || lower.contains("alert") ||
            lower.contains("portal") || lower.contains("where") ||
            lower.contains("bus ") || lower == "bus"
        ) {
            return null
        }
        return value.takeIf { it.isNotBlank() }
    }

    private fun pickBus(pairs: Map<String, String>): String? {
        val value = pick(
            pairs,
            "bus number", "bus no", "bus #", "bus", "vehicle number", "vehicle",
            reject = { label -> label.contains("stop") || label.contains("route") },
        )
        // A bus number normally contains a digit. This filters out "Maple Ave".
        return value?.takeIf { it.any(Char::isDigit) }
    }

    private fun pickRoute(pairs: Map<String, String>): String? = pick(
        pairs,
        "route number", "route no", "route #", "route",
        "run number", "run no", "run",
        reject = { label -> label.contains("stop") || label.contains("map") },
    )

    private fun pickStop(pairs: Map<String, String>): String? = pick(
        pairs,
        "bus stop", "stop name", "stop", "am stop", "pm stop",
        reject = { label -> label.contains("address") || label.contains("time") },
    )

    /**
     * Returns a value for one of [keys]. Exact matches win. Then we accept
     * labels that consist of the key plus a qualifier (e.g. "bus number" for
     * key "bus"), unless the label is rejected by the caller.
     */
    private fun pick(
        pairs: Map<String, String>,
        vararg keys: String,
        reject: (String) -> Boolean = { false },
    ): String? {
        for (key in keys) {
            val normalized = normalizeKey(key)
            pairs[normalized]?.let { if (it.isNotBlank()) return it }
        }
        for (key in keys) {
            val normalized = normalizeKey(key)
            pairs.entries.firstOrNull { (label, value) ->
                value.isNotBlank() && !reject(label) && (
                    label.startsWith("$normalized ") ||
                        label.endsWith(" $normalized") ||
                        label.contains(" $normalized ")
                    )
            }?.let { return it.value }
        }
        return null
    }

    private fun findEtaGauge(scripts: String): EtaGauge? {
        val match = etaGaugeRegex.find(scripts) ?: return null
        val minutes = match.groupValues[1].toDoubleOrNull() ?: return null
        return EtaGauge(
            minutes = minutes.roundToInt(),
            title = match.groupValues[2].cleanValue().takeIf { it.isNotBlank() },
        )
    }

    /**
     * Reads the live page's hidden JSON inputs. Each entry looks like:
     *   {"Latitude":43.1,"Longitude":-79.2,"Label":"..."}
     * The page's own JavaScript treats 0/-1 coordinates as "no fix".
     */
    private fun findLiveBus(doc: Document): LiveBus? {
        val busJson = doc.selectFirst("#MainContent_NestContent_hfBusLocation")?.attr("value")
        val stopJson = doc.selectFirst("#MainContent_NestContent_hfBusStopLocations")?.attr("value")

        val buses = parsePositions(busJson)
        val stops = parsePositions(stopJson)
        if (buses.isEmpty() && stops.isEmpty()) return null

        val bus = buses.firstOrNull { it.position != null }
        val stop = stops.firstOrNull { it.position != null }

        return LiveBus(
            position = bus?.position,
            label = bus?.label,
            etaMinutes = bus?.etaMinutes,
            etaText = bus?.etaText,
            stopPosition = stop?.position,
        )
    }

    private fun parsePositions(raw: String?): List<Position> {
        val json = raw?.trim().orEmpty()
        if (json.isBlank() || json == "null") return emptyList()

        return runCatching {
            when {
                json.startsWith("[") -> {
                    val array = JSONArray(json)
                    (0 until array.length()).mapNotNull { index ->
                        array.optJSONObject(index)?.let(::positionFrom)
                    }
                }

                json.startsWith("{") -> listOfNotNull(positionFrom(JSONObject(json)))
                else -> emptyList()
            }
        }.getOrDefault(emptyList())
    }

    private fun positionFrom(obj: JSONObject): Position? {
        val lat = obj.optDoubleAny("Latitude", "latitude", "Lat", "lat")
        val lon = obj.optDoubleAny("Longitude", "longitude", "Lng", "lng", "Lon", "lon")
        val valid = lat != null && lon != null &&
            lat in -90.0..90.0 && lon in -180.0..180.0 &&
            lat != 0.0 && lon != 0.0 && lat != -1.0 && lon != -1.0

        val label = obj.optStringAny("Label", "label", "RunLabel", "RunID", "RunId", "Name")
            ?.takeIf { it.isNotBlank() }

        val etaValue = obj.optStringAny(
            "ETA", "Eta", "eta", "EstimatedMinutes", "Minutes", "EtaMinutes", "EstimatedTime",
        )
        val etaMinutes = etaValue?.let { value ->
            Regex("-?\\d{1,3}").find(value)?.value?.toIntOrNull()
        }?.takeIf { it in -30..240 }

        val etaText = etaValue?.takeIf { it.any(Char::isLetter) }

        return Position(
            position = if (valid) lat!! to lon!! else null,
            label = label,
            etaMinutes = etaMinutes,
            etaText = etaText,
        )
    }

    private fun JSONObject.optDoubleAny(vararg names: String): Double? {
        for (name in names) {
            if (!has(name)) continue
            val value = optDouble(name, Double.NaN)
            if (!value.isNaN() && value.isFinite()) return value
        }
        return null
    }

    private fun JSONObject.optStringAny(vararg names: String): String? {
        for (name in names) {
            if (!has(name)) continue
            val value = optString(name, "").trim()
            if (value.isNotBlank() && value != "null") return value
        }
        return null
    }

    private fun findBusMarker(html: String): Pair<Double, Double>? =
        findMarker(html, stop = false)

    private fun findStopMarker(html: String): Pair<Double, Double>? =
        findMarker(html, stop = true)

    private fun findMarker(html: String, stop: Boolean): Pair<Double, Double>? {
        for (match in addMarkerRegex.findAll(html)) {
            val type = match.groupValues[3].lowercase()
            val isStop = type.contains("stop")
            if (isStop != stop) continue
            if (!stop && !type.contains("bus") && !type.contains("vehicle")) continue
            val lat = match.groupValues[1].toDoubleOrNull() ?: continue
            val lon = match.groupValues[2].toDoubleOrNull() ?: continue
            if (lat in -90.0..90.0 && lon in -180.0..180.0) return lat to lon
        }
        return null
    }

    private fun findTimeNear(text: String, vararg anchors: String): String? {
        for (anchor in anchors) {
            val anchorRegex = Regex("""\b${Regex.escape(anchor)}\b""", RegexOption.IGNORE_CASE)
            for (match in anchorRegex.findAll(text)) {
                val start = (match.range.first - 40).coerceAtLeast(0)
                val end = (match.range.last + 140).coerceAtMost(text.length)
                val window = text.substring(start, end)
                timeWithMeridiem.find(window)?.let {
                    return it.groupValues[1].replace(Regex("\\s+"), " ").uppercase()
                }
                bareTime.find(window)?.let { return it.groupValues[1] }
            }
        }
        return null
    }

    private fun findStatusNote(doc: Document): String? {
        val selectors = listOf(
            "#lblBulletin",
            "#MainContent_NestContent_lblMapDelays",
            "#UpdatePanel_Bulletin",
            ".alert-danger",
            ".alert-warning",
            ".alert-info",
        )

        val alertText = selectors.asSequence()
            .flatMap { doc.select(it).asSequence() }
            .map { it.text().cleanAlertText() }
            .filter {
                it.isNotBlank() &&
                    !it.equals("No Alerts", ignoreCase = true) &&
                    !it.equals("Close", ignoreCase = true)
            }
            .distinct()
            .joinToString(" • ")

        if (alertText.isNotBlank()) return alertText.take(280)

        val text = doc.body()?.text().orEmpty()
        val phrases = listOf(
            "bus is delayed", "route is delayed", "bus is cancelled", "bus is canceled",
            "route is cancelled", "route is canceled", "no gps", "gps unavailable",
            "vehicle associated to your child's transportation is unavailable",
        )
        val found = phrases.firstOrNull { text.contains(it, ignoreCase = true) } ?: return null
        val index = text.indexOf(found, ignoreCase = true)
        val start = (index - 40).coerceAtLeast(0)
        val end = (index + found.length + 80).coerceAtMost(text.length)
        return text.substring(start, end).cleanValue()
    }

    private fun textOf(doc: Document, selector: String): String? =
        doc.selectFirst(selector)?.text()?.cleanValue()?.takeIf { it.isNotBlank() }

    private fun firstMatch(regex: Regex, input: String): String? =
        regex.find(input)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }

    private fun normalizeKey(raw: String): String = raw
        .replace('\u00A0', ' ')
        .lowercase()
        .trim()
        .trimEnd(':', '*')
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun String.cleanValue(): String = replace('\u00A0', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()

    /**
     * Placeholder options from empty form controls. The live map page embeds an
     * "Add Student" form, so its unselected dropdowns must not look like data.
     */
    private fun String.isPlaceholder(): Boolean {
        val value = cleanValue().lowercase()
        return value.isBlank() ||
            value == "--" || value == "-" || value == "n/a" || value == "na" ||
            value.startsWith("--") ||
            value == "select" || value.startsWith("select ") ||
            value.startsWith("select...") ||
            value == "choose" || value.startsWith("choose ") ||
            value.startsWith("please select")
    }

    private fun String.cleanAlertText(): String = cleanValue()
        .removeSuffix("×")
        .removeSuffix("Close")
        .cleanValue()

    private fun String.titleCaseWords(): String = split(Regex("\\s+"))
        .filter { it.isNotBlank() }
        .joinToString(" ") { word ->
            word.lowercase().replaceFirstChar { it.titlecase() }
        }

    private fun String.normalizeTime(): String {
        val clean = cleanValue()
        val match = timeWithMeridiem.find(clean) ?: return clean
        return match.groupValues[1].replace(Regex("\\s+"), " ").uppercase()
    }

    private data class Leg(
        val index: Int,
        val type: String?,
        val time: String?,
        val location: String?,
        val route: String?,
    )

    private data class Trip(
        /** true = from school, false = to school, null = not stated. */
        val afternoon: Boolean?,
        val pickup: Leg?,
        val dropoff: Leg?,
    )

    private data class EtaGauge(
        val minutes: Int,
        val title: String?,
    )

    private data class Position(
        val position: Pair<Double, Double>?,
        val label: String?,
        val etaMinutes: Int?,
        val etaText: String?,
    )

    private data class LiveBus(
        val position: Pair<Double, Double>?,
        val label: String?,
        val etaMinutes: Int?,
        val etaText: String?,
        val stopPosition: Pair<Double, Double>?,
    )
}
