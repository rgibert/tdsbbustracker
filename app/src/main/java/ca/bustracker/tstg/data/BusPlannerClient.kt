package ca.bustracker.tstg.data

import ca.bustracker.tstg.util.Constants
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Small native client for the TSTG BusPlanner parent portal.
 *
 * The login flow is a standard ASP.NET WebForms postback:
 *   GET the login page -> keep cookies + hidden fields -> POST credentials
 *   with __EVENTTARGET set to the login button -> follow the redirect.
 *
 * No credentials are ever sent anywhere except tstg.mybusplanner.ca.
 */
class BusPlannerClient(
    private val cookieJar: MemoryCookieJar = MemoryCookieJar(),
) {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", Constants.USER_AGENT)
                .header("Accept-Language", "en-CA,en;q=0.9")
                .build()
            chain.proceed(request)
        }
        .build()

    /**
     * Signs in and confirms that the portal actually opened the student page.
     * Throws [LoginException] for bad credentials or a changed login flow.
     */
    fun login(email: String, password: String, remember: Boolean = true) {
        val loginUrl = Constants.LOGIN_URL

        val loginPage = execute(
            Request.Builder()
                .url(loginUrl)
                .header("Accept", HTML_ACCEPT)
                .header("Upgrade-Insecure-Requests", "1")
                .get()
                .build(),
        )
        val form = LoginForm.parse(loginPage)
            ?: throw LoginException(
                "Couldn't read the portal login form. The site may have changed; try opening it in your browser.",
            )

        val postRequest = Request.Builder()
            .url(loginUrl)
            .header("Accept", HTML_ACCEPT)
            .header("Referer", loginUrl)
            .header("Origin", Constants.BASE_URL)
            .header("Upgrade-Insecure-Requests", "1")
            .post(form.toFormBody(email, password, remember))
            .build()

        val response = executeRaw(postRequest)
        response.use { result ->
            if (!result.isSuccessful) {
                throw PortalHttpException("The portal returned HTTP ${result.code}. Try again in a moment.")
            }
            val html = result.body?.string().orEmpty()
            val finalUrl = result.request.url.toString()

            if (finalUrl.contains("/Login", ignoreCase = true) || BusPlannerParser.isLoginPage(html)) {
                throw LoginException(
                    BusPlannerParser.extractLoginError(html)
                        ?: "Sign-in failed. Check your email and password.",
                )
            }
        }
    }

    /**
     * Fetches the logged-in student details page, switched to [studentId]
     * when the account has several students.
     */
    fun fetchChildPage(studentId: String? = null): PortalPage =
        fetchForStudent(Constants.CHILD_PAGE_URL, studentId)

    /**
     * Fetches the live map/ETA page. The current GPS position and ETA are
     * embedded in this page's hidden fields and inline scripts.
     */
    fun fetchLivePage(studentId: String? = null): PortalPage =
        fetchForStudent(Constants.WHERES_MY_BUS_URL, studentId)

    /**
     * Loads [url]; if it is showing a different student than [studentId],
     * replays the portal's "Select student" postback and returns that page.
     */
    private fun fetchForStudent(url: String, studentId: String?): PortalPage {
        val page = fetchPage(url)
        if (studentId.isNullOrBlank()) return page
        val form = BusPlannerParser.studentSwitchForm(page.html, page.url, studentId)
            ?: return page
        return postPage(form, referer = page.url)
    }

    private fun postPage(form: PostbackForm, referer: String): PortalPage {
        val body = FormBody.Builder().apply {
            form.fields.forEach { (name, value) -> add(name, value) }
        }.build()
        val response = executeRaw(
            Request.Builder()
                .url(form.url)
                .header("Accept", HTML_ACCEPT)
                .header("Referer", referer)
                .header("Origin", Constants.BASE_URL)
                .header("Cache-Control", "no-cache, no-store, max-age=0")
                .post(body)
                .build(),
        )
        return readPage(response)
    }

    fun fetchAlertsPage(): PortalPage = fetchPage(Constants.ALERTS_URL)

    private fun fetchPage(url: String): PortalPage {
        val response = executeRaw(
            Request.Builder()
                .url(url)
                .header("Accept", HTML_ACCEPT)
                .header("Cache-Control", "no-cache, no-store, max-age=0")
                .header("Pragma", "no-cache")
                .get()
                .build(),
        )
        return readPage(response)
    }

    private fun readPage(response: okhttp3.Response): PortalPage = response.use { result ->
        if (!result.isSuccessful) {
            throw PortalHttpException("The portal returned HTTP ${result.code}. Try again in a moment.")
        }
        val html = result.body?.string().orEmpty()
        val finalUrl = result.request.url.toString()
        if (finalUrl.contains("/Login", ignoreCase = true) || BusPlannerParser.isLoginPage(html)) {
            throw SessionExpiredException()
        }
        PortalPage(html = html, url = finalUrl)
    }

    fun cookieSnapshot(): List<WebCookie> = cookieJar.snapshot().map { cookie ->
        WebCookie(
            name = cookie.name,
            value = cookie.value,
            domain = cookie.domain,
            path = cookie.path,
            secure = cookie.secure,
            httpOnly = cookie.httpOnly,
        )
    }

    fun clearSession() {
        cookieJar.clear()
    }

    private fun execute(request: Request): String = executeRaw(request).use { response ->
        if (!response.isSuccessful) {
            throw PortalHttpException("The portal returned HTTP ${response.code}.")
        }
        response.body?.string().orEmpty()
    }

    private fun executeRaw(request: Request) = client.newCall(request).execute()

    private class LoginForm(private val hiddenFields: Map<String, String>) {

        fun toFormBody(email: String, password: String, remember: Boolean): FormBody {
            val builder = FormBody.Builder()
            hiddenFields.forEach { (name, value) ->
                if (name !in REPLACED_FIELDS) builder.add(name, value)
            }
            builder.add("__EVENTTARGET", LOGIN_BUTTON)
            builder.add("__EVENTARGUMENT", "")
            builder.add("__LASTFOCUS", "")
            builder.add(USERNAME_FIELD, email.trim())
            builder.add(PASSWORD_FIELD, password)
            builder.add("ctl00\$ddlLanguages", "en-CA")
            if (remember) builder.add(REMEMBER_FIELD, "on")
            return builder.build()
        }

        companion object {
            private const val LOGIN_BUTTON = "ctl00\$MainContent\$glogin\$lLogin\$btnLogin"
            private const val USERNAME_FIELD = "ctl00\$MainContent\$glogin\$lLogin\$UserName"
            private const val PASSWORD_FIELD = "ctl00\$MainContent\$glogin\$lLogin\$Password"
            private const val REMEMBER_FIELD = "ctl00\$MainContent\$glogin\$lLogin\$RememberMe"

            private val REPLACED_FIELDS = setOf(
                "__EVENTTARGET",
                "__EVENTARGUMENT",
                "__LASTFOCUS",
                USERNAME_FIELD,
                PASSWORD_FIELD,
                REMEMBER_FIELD,
                "ctl00\$ddlLanguages",
            )

            fun parse(html: String): LoginForm? {
                val doc = Jsoup.parse(html)
                val form = doc.selectFirst("form#mainForm")
                    ?: doc.selectFirst("form:has(input[name=__VIEWSTATE])")
                    ?: doc.selectFirst("form")
                    ?: return null
                val fields = linkedMapOf<String, String>()
                form.select("input[type=hidden]").forEach { input ->
                    val name = input.attr("name")
                    if (name.isNotBlank()) fields[name] = input.attr("value")
                }
                if (fields["__VIEWSTATE"].isNullOrBlank()) return null
                return LoginForm(fields)
            }
        }
    }

    companion object {
        private const val HTML_ACCEPT =
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
    }
}

class MemoryCookieJar : CookieJar {
    private val cookies = ConcurrentHashMap<String, Cookie>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookies.forEach { cookie ->
            this.cookies[key(cookie)] = cookie
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        return cookies.values.filter { cookie ->
            cookie.expiresAt > now && cookie.matches(url)
        }
    }

    fun snapshot(): List<Cookie> {
        val now = System.currentTimeMillis()
        return cookies.values.filter { it.expiresAt > now }
    }

    fun clear() = cookies.clear()

    private fun key(cookie: Cookie): String =
        "${cookie.domain}|${cookie.path}|${cookie.name}"
}
