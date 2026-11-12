package ca.bustracker.tstg.data

import java.io.IOException

data class Credentials(
    val email: String,
    val password: String,
)

data class WebCookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String,
    val secure: Boolean,
    val httpOnly: Boolean,
) {
    /**
     * Cookie header value understood by android.webkit.CookieManager.
     * HttpOnly cookies must still be sent by the WebView's HTTP stack; the
     * flag only hides them from JavaScript.
     */
    fun toSetCookieString(): String = buildString {
        append(name).append('=').append(value)
        append("; Path=").append(path.ifBlank { "/" })
        if (domain.startsWith('.')) append("; Domain=").append(domain)
        if (secure) append("; Secure")
        if (httpOnly) append("; HttpOnly")
    }
}

/** One entry from the portal's "Select student" dropdown. */
data class StudentOption(
    /** The dropdown option value the portal uses to identify the student. */
    val id: String,
    val name: String,
    val selected: Boolean = false,
)

data class ChildInfo(
    val studentName: String? = null,
    val school: String? = null,
    /** Every student on the account, in portal order. Empty for one-student pages. */
    val students: List<StudentOption> = emptyList(),
    /** Portal id of the student these details belong to. */
    val selectedStudentId: String? = null,
    val busNumber: String? = null,
    val route: String? = null,
    val stopName: String? = null,
    val stopAddress: String? = null,
    /** Morning pick-up time at the student's stop. */
    val amTime: String? = null,
    /** Afternoon drop-off time at the student's stop (not the school pick-up). */
    val pmTime: String? = null,
    val etaMinutes: Int? = null,
    val etaText: String? = null,
    val statusNote: String? = null,
    val busLatitude: Double? = null,
    val busLongitude: Double? = null,
    val busLabel: String? = null,
    val stopLatitude: Double? = null,
    val stopLongitude: Double? = null,
    val lastPositionText: String? = null,
    val mapDelayText: String? = null,
) {
    val hasAnything: Boolean
        get() = listOf(
            studentName, school, busNumber, route, stopName, stopAddress,
            amTime, pmTime, etaText, statusNote, busLabel, lastPositionText,
            mapDelayText,
        ).any { !it.isNullOrBlank() } || etaMinutes != null

    /**
     * The portal packs several values into one label, e.g. "Last updated
     * 8:15 AM latest Last reported 8:14 AM Run 12A". Splits it into the
     * reported time (shown next to the AM/PM times) and the run (its own
     * row). The "updated" time is dropped, and "Last" is taken off labels.
     */
    val positionDetails: PositionDetails
        get() {
            val text = lastPositionText?.trim().orEmpty()
            if (text.isEmpty()) return PositionDetails()
            val starts = POSITION_PART_REGEX.findAll(text).map { it.range.first }.toMutableList()
            if (starts.firstOrNull() != 0) starts.add(0, 0)
            val times = mutableListOf<PositionTime>()
            val other = mutableListOf<String>()
            var run: String? = null
            starts.forEachIndexed { i, start ->
                val end = starts.getOrNull(i + 1) ?: text.length
                val part = text.substring(start, end).cleanPart()
                    .replace(TRAILING_LATEST_REGEX, "").cleanPart()
                if (part.isEmpty()) return@forEachIndexed
                if (RUN_REGEX.containsMatchIn(part)) {
                    run = part
                    return@forEachIndexed
                }
                val time = TIME_REGEX.find(part)
                if (time == null) {
                    other += part
                    return@forEachIndexed
                }
                val label = part.substring(0, time.range.first).cleanPart().trimEnd(':').trim()
                    .replace(LEADING_LAST_REGEX, "")
                // The card already shows when the app last refreshed, so the
                // portal's own "updated" time would just repeat it.
                if (!UPDATED_REGEX.containsMatchIn(label)) {
                    times += PositionTime(
                        label = label.replaceFirstChar { it.uppercase() }.ifEmpty { "Position" },
                        time = time.value.trim(),
                    )
                }
                part.substring(time.range.last + 1).cleanPart().takeIf { it.isNotEmpty() }
                    ?.let { other += it }
            }
            return PositionDetails(times, run, other)
        }

    val hasLiveBus: Boolean
        get() = busLatitude != null && busLongitude != null

    val displayName: String
        get() = studentName?.takeIf { it.isNotBlank() } ?: "My student"

    val busAndRoute: String?
        get() {
            val parts = buildList {
                busNumber?.takeIf { it.isNotBlank() }?.let { add("Bus $it") }
                route?.takeIf { it.isNotBlank() }?.let { add("Route $it") }
                if (size == 0 && !busLabel.isNullOrBlank()) add("Run $busLabel")
            }
            return parts.takeIf { it.isNotEmpty() }?.joinToString(" • ")
        }

    /**
     * Combines two parses of the portal. The receiver wins (use the live
     * Where's My Bus page for GPS/ETA); [fallback] fills in student details
     * that only appear on the ChildTransportInfo page.
     */
    fun withFallback(fallback: ChildInfo): ChildInfo = ChildInfo(
        studentName = studentName ?: fallback.studentName,
        school = school ?: fallback.school,
        students = students.ifEmpty { fallback.students },
        selectedStudentId = selectedStudentId ?: fallback.selectedStudentId,
        busNumber = busNumber ?: fallback.busNumber,
        route = route ?: fallback.route,
        stopName = stopName ?: fallback.stopName,
        stopAddress = stopAddress ?: fallback.stopAddress,
        amTime = amTime ?: fallback.amTime,
        pmTime = pmTime ?: fallback.pmTime,
        etaMinutes = etaMinutes ?: fallback.etaMinutes,
        etaText = etaText ?: fallback.etaText,
        statusNote = statusNote ?: fallback.statusNote,
        busLatitude = busLatitude ?: fallback.busLatitude,
        busLongitude = busLongitude ?: fallback.busLongitude,
        busLabel = busLabel ?: fallback.busLabel,
        stopLatitude = stopLatitude ?: fallback.stopLatitude,
        stopLongitude = stopLongitude ?: fallback.stopLongitude,
        lastPositionText = lastPositionText ?: fallback.lastPositionText,
        mapDelayText = mapDelayText ?: fallback.mapDelayText,
    )
}

/** A form post that replays one of the portal's own ASP.NET postbacks. */
data class PostbackForm(
    val url: String,
    val fields: Map<String, String>,
)

data class PortalPage(
    val html: String,
    val url: String,
)

open class PortalException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

class LoginException(message: String) : PortalException(message)

class SessionExpiredException :
    PortalException("Your portal session expired. Signing in again…")

class PortalHttpException(message: String) : PortalException(message)

/** A labelled time from the portal's latest-position label. */
data class PositionTime(val label: String, val time: String)

/** The portal's latest-position label, split into its parts. */
data class PositionDetails(
    val times: List<PositionTime> = emptyList(),
    /** e.g. "Run 12A". */
    val run: String? = null,
    /** Anything that was not recognised, shown as-is. */
    val other: List<String> = emptyList(),
)

/** Start of each part of the portal's latest-position label. */
private val POSITION_PART_REGEX = Regex(
    "\\b(?:(?:last|latest)\\s+)?(?:updated|reported|received)\\b|\\brun\\b",
    RegexOption.IGNORE_CASE,
)
private val RUN_REGEX = Regex("^run\\b", RegexOption.IGNORE_CASE)
private val LEADING_LAST_REGEX = Regex("^(?:last|latest)\\s+", RegexOption.IGNORE_CASE)
private val UPDATED_REGEX = Regex("^updated\\b", RegexOption.IGNORE_CASE)
private val TRAILING_LATEST_REGEX = Regex("\\s*\\blatest$", RegexOption.IGNORE_CASE)
private val TIME_REGEX = Regex(
    "\\b\\d{1,2}:\\d{2}(?::\\d{2})?(?:\\s*[ap]\\.?\\s?m\\.?)?",
    RegexOption.IGNORE_CASE,
)

private fun String.cleanPart(): String =
    trim().trim(',', ';', '|', '·', '•', '-').trim()
