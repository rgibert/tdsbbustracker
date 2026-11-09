package ca.bustracker.tstg.util

object Constants {
    const val BASE_URL = "https://tstg.mybusplanner.ca"

    // This is the exact entry point used by the official parent-portal link.
    // Keeping the same query string makes the ASP.NET login handshake behave
    // exactly like a normal browser session.
    const val LOGIN_PATH =
        "/Login?ReturnUrl=%2fSubscriptions%2fChildTransportInfo&LoginType=Subscriber&showParentPopup=True"

    const val LOGIN_URL = BASE_URL + LOGIN_PATH

    // Student details (school, bus, route, stop, scheduled times).
    const val CHILD_PAGE_URL = "$BASE_URL/Subscriptions/ChildTransportInfo"

    // The real live map + ETA page. It holds the current GPS position in the
    // hidden hfBusLocation field and renders the Leaflet map.
    const val WHERES_MY_BUS_URL = "$BASE_URL/Subscriptions/WheresMyBus"

    const val ALERTS_URL = "$BASE_URL/Alerts"

    // Must be identical for OkHttp and the WebView. The portal is responsive;
    // a mobile Chrome UA gives us the phone layout.
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    const val PREF_FILE = "bus_tracker_settings"
    const val SECURE_PREF_FILE = "bus_tracker_secure"
}
