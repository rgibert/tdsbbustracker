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
