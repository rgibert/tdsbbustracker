# Bus Tracker (TSTG / BusPlanner)

[![Build APKs](https://github.com/rgibert/tdsbbustracker/actions/workflows/build.yml/badge.svg)](https://github.com/rgibert/tdsbbustracker/actions/workflows/build.yml)

A small, unofficial Android app for tracking a school bus on the
[TSTG BusPlanner parent portal](https://tstg.mybusplanner.ca/).

It is built for one job: open the app, and immediately see the live bus map
and ETA without typing a password every morning.

> **Unofficial.** This app is not affiliated with, endorsed by, or supported by
> TSTG or BusPlanner. It simply signs in to the same parent web portal you
> already use.

## What it does

- **One-tap sign-in.** Your email and password are stored on the phone,
  encrypted with a non-exportable Android Keystore key. The password is only
  ever sent to `tstg.mybusplanner.ca`.
- **Live bus map.** A native OpenStreetMap map shows just the bus and your stop,
  using the GPS positions the portal publishes on its Where's My Bus page. It
  follows the bus until you touch the map; the re-centre button follows it again.
- **Native summary card.** The app reads the portal's hidden GPS data and ETA
  gauge and shows a quick "12 min to your stop" card above the map.
- **Alerts.** Toggle to the TSTG alerts page from the top bar.
- **Auto-refresh.** The summary and the map refresh on a configurable
  interval (default 60 seconds).
- **Session recovery.** If the portal session expires, the app signs in again
  with the saved credentials automatically.

## Requirements

- Android 8.0 (API 26) or newer.
- An active TSTG BusPlanner parent account.
- Internet access.

## Build the APK

### Android Studio (easiest)

1. Open this folder in Android Studio.
2. Let Gradle sync and install any missing SDK 35 packages.
3. Choose **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
4. The debug APK is written to:
   `app/build/outputs/apk/debug/app-debug.apk`

### Command line

You need JDK 17 and Android SDK platform 35 + build-tools 35.0.0.

```sh
export JAVA_HOME=/path/to/jdk-17
export ANDROID_SDK_ROOT=/path/to/Android/Sdk
./gradlew assembleDebug
```

The APK is at `app/build/outputs/apk/debug/app-debug.apk`.

If Gradle cannot find the SDK, create `local.properties` in this folder:

```properties
sdk.dir=/path/to/Android/Sdk
```

## Continuous integration

`.github/workflows/build.yml` runs on pull requests to `main` and on `v*` tags.
It runs the parser unit tests, builds and lints the debug APK, uploads the APK
and the test/lint reports, and comments the APK artifact link on the pull
request.

No repository secrets are needed for the debug build. The APK is signed with
the standard Android debug key, which is fine for personal sideloading.

### Release signing (optional)

Tag builds (`vX.Y.Z`) publish a signed release APK when these repository
secrets are set:

| Secret | Value |
| --- | --- |
| `BUS_TRACKER_KEYSTORE_BASE64` | the keystore file, base64-encoded |
| `BUS_TRACKER_KEYSTORE_PASSWORD` | keystore password |
| `BUS_TRACKER_KEY_ALIAS` | key alias |
| `BUS_TRACKER_KEY_PASSWORD` | key password |

Create the keystore once and keep it backed up:

```sh
keytool -genkeypair -keystore release.jks -alias bustracker \
  -keyalg RSA -keysize 2048 -validity 10000
base64 < release.jks | tr -d '\n'
```

Without those secrets, tag builds still succeed but produce an unsigned
release APK; the GitHub release then contains only the installable debug APK.

## Install on the phone (sideload)

The build is a debug APK signed with the standard Android debug key. That is
fine for personal sideloading; it just cannot be uploaded to the Play Store.

With USB debugging enabled:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or copy the APK to the phone and tap it. Android will ask you to allow installs
from that app (Files/Chrome) the first time.

## First run

1. Enter the email and password you use for the TSTG parent portal.
2. Leave **Remember me** on if you want one-tap sign-in next time.
3. Tap **Sign in**.

The app signs in with a normal ASP.NET WebForms postback, copies the session
cookies into the WebView used for alerts, and opens the live map. You should not see the portal
login page again.

## Settings

- **Refresh summary every** — 30 seconds to 5 minutes.
- **Open the app on** — accounts with several students only: always start on
  a chosen student, or on the last student viewed.
- **Compact portal** — hide the portal header, footer, menus, and quick links
  on the alerts page.
- **Open TSTG alerts page** — jump to the portal's alerts view.
- **Save logged-in pages for tuning** — writes the student and live-map HTML
  files and opens the Android share sheet.
- **Sign out and forget saved password** — clears the saved credentials and the
  portal session.

## How it works

1. `BusPlannerClient` performs the portal login with OkHttp:
   - GET the login page and keep the ASP.NET cookies and hidden fields.
   - POST the credentials with the correct `__EVENTTARGET`.
   - Verify that the response is not the login form.
2. The app fetches two pages:
   - `/Subscriptions/ChildTransportInfo` for school, bus, route, stop, and
     scheduled AM/PM times.
   - `/Subscriptions/WheresMyBus` for live GPS, ETA, and delays.
3. `BusPlannerParser` reads:
   - `#MainContent_NestContent_hfBusLocation` (JSON array of positions)
   - `#MainContent_NestContent_hfBusStopLocations`
   - `#MainContent_NestContent_lblLatestPosition`
   - `#MainContent_NestContent_lblMapDelays`
   - `#lblBulletin`
   - `SetETAGauge(...)` inline script calls
   - label/value tables as a fallback
4. The map (`BusMap.kt`, osmdroid) draws the bus from `hfBusLocation` and the
   stop from `hfBusStopLocations`. Tiles come from OpenStreetMap, with the
   attribution its tile policy requires. The alerts page is still the portal's
   own page in a WebView, with the OkHttp cookies copied into Android's
   `CookieManager` so it does not ask for a second login.

## Privacy and security

- Credentials are encrypted with AES/GCM using a key held by the Android
  Keystore. The key is non-exportable; the app never logs the password.
- The only network destination for credentials is `https://tstg.mybusplanner.ca`.
- No analytics, ads, or third-party crash reporting are included.
- **Debug HTML contains personal information.** The saved pages include your
  child's name, stop, route, and possibly live GPS coordinates. Share them only
  with someone you trust, such as the person helping tune the parser.

## Tuning the parser

The parser is intentionally tolerant of layout changes, but BusPlanner can
change its markup. If the native summary card is empty while the WebView map
works:

1. Open **Settings > Save logged-in pages for tuning**.
2. Share the two HTML files with the developer.
3. The parser lives in
   `app/src/main/java/ca/bustracker/tstg/data/BusPlannerParser.kt`.

Two quirks of the real portal are already handled:

- The Where's My Bus page also embeds the hidden **Add Student** form, including
  a huge school dropdown whose placeholder is `--Select--`. The parser only
  reads student details (school, bus, route, stop, AM/PM times) from
  `/Subscriptions/ChildTransportInfo`, so those form values never overwrite
  real data.
- A hidden date/time picker injects strings like `12:00 AM` into the page text.
  Hidden widgets are stripped before regex fallbacks run.
- Each trip on ChildTransportInfo ("To School", "From School") has its own
  pick-up and drop-off, so the first "Dropoff" on the page is the morning
  drop-off at the school. The parser reads the trips' `lblStopType` /
  `lblArrivalValue` fields directly: **AM pickup** is the morning pick-up at the
  stop and **PM drop-off** is the afternoon drop-off at the stop.
- Accounts with several students get a student picker on the summary card. The
  app replays the portal's own `ddlStudents` postback for the chosen student,
  and remembers the choice. **Settings > Open the app on** picks a default
  student to start with every time; "Last viewed" (the default) reopens on
  whoever you looked at last.

Unit tests with synthetic BusPlanner markup are in
`app/src/test/java/ca/bustracker/tstg/data/BusPlannerParserTest.kt`. Run them
with:

```sh
./gradlew testDebugUnitTest
```

## Troubleshooting

| Symptom | What to try |
| --- | --- |
| "Email or password is incorrect" | Sign in to the portal in a browser to confirm the password. The app does not bypass TSTG's own login rules. |
| Map says "No live bus position right now" | The portal has no GPS fix for the bus (it is not on the road yet, or TSTG shows "vehicle information unavailable"). The bus appears on the next refresh after it does. |
| Map tiles are blank | Check that the phone can reach `tile.openstreetmap.org`. The bus and stop markers still update without tiles. |
| App returns to the login screen | The saved password may have changed. Sign in again; the app clears a stored password that no longer works. |
| Portal shows a Code of Conduct popup | The WebView automatically accepts visible acknowledgement dialogs. If a new popup appears, capture the page and report it. |

## Project layout

```
app/src/main/java/ca/bustracker/tstg/
  data/
    BusPlannerClient.kt      OkHttp login + page fetching
    BusPlannerParser.kt      HTML/JSON/ETA parser
    Models.kt                ChildInfo, cookies, exceptions
    SecureStore.kt           Android Keystore encrypted credentials
    AppPrefs.kt              refresh interval + compact mode
  ui/
    App.kt                   root phase switching
    AppViewModel.kt          auth, refresh, session recovery
    LoginScreen.kt           credentials form
    MainScreen.kt            summary card + map or alerts
    BusMap.kt                native OpenStreetMap bus/stop map
    PortalWebView.kt         alerts WebView: cookie sync, compact CSS
    SummaryCard.kt           native ETA/bus/stop card
    SettingsDialog.kt        settings and debug capture
  util/
    Constants.kt             portal URLs and user agent
    DebugExport.kt           writes and shares logged-in HTML
```

## License

Personal use. Use at your own risk and in accordance with TSTG's terms of use.
