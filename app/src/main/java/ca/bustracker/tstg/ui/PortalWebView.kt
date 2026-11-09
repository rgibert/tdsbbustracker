package ca.bustracker.tstg.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import ca.bustracker.tstg.data.WebCookie
import ca.bustracker.tstg.util.Constants

/**
 * The real BusPlanner portal in a WebView. Reusing the portal means the live
 * GPS map, ETA gauge, and any new portal features keep working even when the
 * native parser does not recognise a layout change.
 *
 * Cookies from the native OkHttp login are copied into the WebView, so the
 * user never has to sign in twice.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PortalWebView(
    url: String,
    compact: Boolean,
    sessionGeneration: Int,
    refreshTick: Int,
    cookies: List<WebCookie>,
    onSessionExpired: () -> Unit,
    modifier: Modifier = Modifier,
    /** Student to show on the map; the portal's own dropdown is switched to it. */
    studentId: String? = null,
    /** Hide the portal's student dropdown because the app shows its own picker. */
    hideStudentSelector: Boolean = false,
) {
    val context = LocalContext.current
    val currentOnExpired by rememberUpdatedState(onSessionExpired)
    val currentCompact by rememberUpdatedState(compact)
    val currentStudentId by rememberUpdatedState(studentId)
    val currentHideSelector by rememberUpdatedState(hideStudentSelector)

    var pageLoads by remember { mutableStateOf(0) }
    var notifiedExpiry by remember { mutableStateOf(false) }

    val webView = remember {
        WebView(context).apply {
            val view = this
            setBackgroundColor(android.graphics.Color.WHITE)
            overScrollMode = View.OVER_SCROLL_NEVER
            isNestedScrollingEnabled = true

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadsImagesAutomatically = true
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                setSupportMultipleWindows(false)
                mediaPlaybackRequiresUserGesture = true
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                cacheMode = WebSettings.LOAD_DEFAULT
                userAgentString = Constants.USER_AGENT
                allowFileAccess = false
                allowContentAccess = false
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean = handleNavigation(context, request.url)

                @Deprecated("Deprecated in Java")
                override fun shouldOverrideUrlLoading(view: WebView, target: String): Boolean =
                    handleNavigation(context, Uri.parse(target))

                override fun onPageStarted(
                    view: WebView,
                    url: String?,
                    favicon: Bitmap?,
                ) {
                    super.onPageStarted(view, url, favicon)
                }

                override fun onPageFinished(view: WebView, loadedUrl: String?) {
                    super.onPageFinished(view, loadedUrl)
                    pageLoads++

                    val target = loadedUrl.orEmpty()
                    if (isLoginUrl(target)) {
                        if (!notifiedExpiry) {
                            notifiedExpiry = true
                            currentOnExpired()
                        }
                        return
                    }

                    view.evaluateJavascript(
                        styleScript(currentCompact, currentHideSelector),
                        null,
                    )
                    currentStudentId?.let { view.evaluateJavascript(selectStudentScript(it), null) }
                    scheduleDialogHandling(view)
                    if (isWheresMyBusUrl(target)) {
                        scheduleMapBootstrap(view)
                    }

                    // The URL can look fine while an expired session still
                    // renders the sign-in form. Check the DOM as a backstop.
                    view.postDelayed({
                        view.evaluateJavascript(LOGIN_FORM_CHECK_JS) { result ->
                            if (result == "true" && !notifiedExpiry) {
                                notifiedExpiry = true
                                currentOnExpired()
                            }
                        }
                    }, 900)
                }
            }

            webChromeClient = object : WebChromeClient() {
                // The parent portal sometimes uses JS dialogs (and its
                // Code of Conduct popup can fall back to one). Accepting them
                // keeps the one-tap flow intact.
                override fun onJsAlert(
                    view: WebView?,
                    url: String?,
                    message: String?,
                    result: JsResult?,
                ): Boolean {
                    result?.confirm()
                    return true
                }

                override fun onJsConfirm(
                    view: WebView?,
                    url: String?,
                    message: String?,
                    result: JsResult?,
                ): Boolean {
                    result?.confirm()
                    return true
                }

                override fun onJsPrompt(
                    view: WebView?,
                    url: String?,
                    message: String?,
                    defaultValue: String?,
                    result: JsPromptResult?,
                ): Boolean {
                    result?.confirm(defaultValue.orEmpty())
                    return true
                }
            }

            // Allow third-party cookies for the map tiles / portal frames.
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(view, true)
            }
        }
    }

    LaunchedEffect(url, sessionGeneration) {
        val manager = CookieManager.getInstance()
        manager.setAcceptCookie(true)
        manager.setAcceptThirdPartyCookies(webView, true)
        cookies.forEach { cookie ->
            manager.setCookie(Constants.BASE_URL, cookie.toSetCookieString())
        }
        manager.flush()

        notifiedExpiry = false
        webView.loadUrl(url)
    }

    // Refresh the portal's own UpdatePanel instead of reloading the whole page.
    // That keeps the map's zoom/pan state and still gets fresh GPS + ETA data.
    LaunchedEffect(refreshTick) {
        if (refreshTick <= 0 || pageLoads == 0) return@LaunchedEffect
        if (isWheresMyBusUrl(url)) {
            webView.evaluateJavascript(PORTAL_REFRESH_JS, null)
            webView.postDelayed({
                if (webView.parent != null) {
                    webView.evaluateJavascript(MAP_BOOTSTRAP_JS, null)
                }
            }, 2200)
        }
    }

    // Re-apply the compact stylesheet when the user toggles it without a reload.
    LaunchedEffect(pageLoads, compact, hideStudentSelector) {
        if (pageLoads > 0) {
            webView.evaluateJavascript(styleScript(compact, hideStudentSelector), null)
        }
    }

    // Follow the student picked in the app without reloading the whole app.
    LaunchedEffect(studentId) {
        if (pageLoads > 0 && studentId != null) {
            webView.evaluateJavascript(selectStudentScript(studentId), null)
        }
    }

    AndroidView(
        factory = { webView },
        modifier = modifier,
    )

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            webView.stopLoading()
            webView.webChromeClient = null
            webView.webViewClient = WebViewClient()
            webView.destroy()
        }
    }
}

private fun isLoginUrl(url: String): Boolean {
    if (url.isBlank()) return false
    val path = Uri.parse(url).path.orEmpty()
    return path.equals("/Login", ignoreCase = true) ||
        path.startsWith("/Login/", ignoreCase = true)
}

private fun isWheresMyBusUrl(url: String): Boolean {
    if (url.isBlank()) return false
    val path = Uri.parse(url).path.orEmpty()
    return path.contains("WheresMyBus", ignoreCase = true)
}

private fun handleNavigation(context: Context, target: Uri): Boolean {
    val scheme = target.scheme.orEmpty().lowercase()
    if (scheme == "about") return false
    if (scheme == "http" || scheme == "https") {
        val host = target.host.orEmpty()
        if (host.equals("mybusplanner.ca", ignoreCase = true) ||
            host.endsWith(".mybusplanner.ca", ignoreCase = true)
        ) {
            return false
        }
    }
    return runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, target))
        true
    }.getOrDefault(false)
}

private fun scheduleDialogHandling(view: WebView) {
    listOf(600L, 1600L, 3200L).forEach { delayMillis ->
        view.postDelayed({
            if (view.parent != null) {
                view.evaluateJavascript(AUTO_ACCEPT_DIALOGS_JS, null)
            }
        }, delayMillis)
    }
}

private fun scheduleMapBootstrap(view: WebView) {
    listOf(800L, 2000L, 4500L).forEach { delayMillis ->
        view.postDelayed({
            if (view.parent != null) {
                view.evaluateJavascript(MAP_BOOTSTRAP_JS, null)
            }
        }, delayMillis)
    }
}

private fun styleScript(compact: Boolean, hideStudentSelector: Boolean = false): String {
    val css = buildString {
        if (compact) append(COMPACT_CSS)
        if (hideStudentSelector) append(HIDE_STUDENT_SELECTOR_CSS)
    }
    return buildString {
        append("(function(){")
        append("var old=document.getElementById('bt-style');if(old){old.remove();}")
        if (css.isNotBlank()) {
            append("var s=document.createElement('style');s.id='bt-style';")
            append("s.textContent=").append(jsString(css)).append(";")
            append("(document.head||document.documentElement).appendChild(s);")
        }
        append(
            "var v=document.querySelector('meta[name=viewport]');" +
                "if(!v){v=document.createElement('meta');v.name='viewport';" +
                "v.content='width=device-width, initial-scale=1';" +
                "(document.head||document.documentElement).appendChild(v);}",
        )
        append("})();")
    }
}

private fun jsString(value: String): String = buildString {
    append('"')
    value.forEach { char ->
        when (char) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (char.code < 32) {
                append("\\u").append(char.code.toString(16).padStart(4, '0'))
            } else {
                append(char)
            }
        }
    }
    append('"')
}

/**
 * Switches the portal's "Select student" dropdown to [studentId] the same way
 * a tap would: change the value and let the portal post back. A per-student
 * attempt counter stops a reload loop if the portal refuses the change.
 */
private fun selectStudentScript(studentId: String): String = """
(function(id){
  var sel=document.querySelector('#MainContent_ddlStudents,select[id*=ddlStudents],select[name*=ddlStudents]');
  if(!sel||sel.value===id){try{sessionStorage.removeItem('btStudentTry');}catch(e){}return;}
  var ok=false;for(var i=0;i<sel.options.length;i++){if(sel.options[i].value===id){ok=true;break;}}
  if(!ok){return;}
  var key='btStudentTry',now=Date.now(),t=null;
  try{t=JSON.parse(sessionStorage.getItem(key)||'null');}catch(e){}
  if(t&&t.id===id&&now-t.at<30000&&t.n>=2){return;}
  var n=(t&&t.id===id&&now-t.at<30000)?t.n+1:1;
  try{sessionStorage.setItem(key,JSON.stringify({id:id,at:now,n:n}));}catch(e){}
  sel.value=id;
  if(sel.getAttribute('onchange')||!window.__doPostBack){
    sel.dispatchEvent(new Event('change',{bubbles:true}));
  }else{
    window.__doPostBack(sel.name,'');
  }
})(${jsString(studentId)});
"""

private const val HIDE_STUDENT_SELECTOR_CSS = """
#MainContent_divStudentSelector{display:none !important;}
"""

private const val LOGIN_FORM_CHECK_JS =
    "(function(){return !!(document.querySelector('#MainContent_glogin_lLogin_UserName')" +
        "||document.querySelector('input[type=password]'));})()"

/**
 * The portal normally calls drawBusAndStops(true) from a server-generated
 * script. Calling it ourselves makes the map appear reliably after a reload or
 * an UpdatePanel refresh, and does nothing when there is no GPS fix yet.
 */
private const val MAP_BOOTSTRAP_JS = """
(function(){
  try {
    var input=document.getElementById('MainContent_NestContent_hfBusLocation');
    if(!input||!input.value){return 'no-bus-data';}
    var data;
    try{data=JSON.parse(input.value);}catch(e){return 'bad-json';}
    if(!data||!data.length){return 'no-bus-data';}
    var hasFix=false;
    for(var i=0;i<data.length;i++){
      var p=data[i]||{};
      if(p.Latitude&&p.Longitude&&p.Latitude!==-1&&p.Longitude!==-1
        &&p.Latitude!==0&&p.Longitude!==0){hasFix=true;break;}
    }
    if(!hasFix){return 'no-fix';}
    if(typeof drawBusAndStops==='function'){
      drawBusAndStops(true);
      return 'map-shown';
    }
    var box=document.getElementById('wheresMyBusOsm');
    if(box){box.style.display='block';}
    return 'no-map-helper';
  }catch(e){return 'error';}
})();
"""

/**
 * Clicks the portal's own Refresh button. The button is registered as an
 * async postback trigger, so the UpdatePanel swaps in fresh map data without a
 * full page reload. If the button is missing, fall back to a reload.
 */
private const val PORTAL_REFRESH_JS = """
(function(){
  try {
    var btn=document.getElementById('MainContent_NestContent_MapRefresh');
    if(btn){btn.click();return 'clicked';}
    if(location.pathname.toLowerCase().indexOf('wheresmybus')>=0){
      location.reload();
      return 'reloaded';
    }
    return 'noop';
  }catch(e){return 'error';}
})();
"""

/**
 * Clicks through the portal's Code of Conduct / acknowledgement modal. It is
 * intentionally limited to visible dialog containers so it cannot click a
 * random "Continue" link in the page body.
 */
private const val AUTO_ACCEPT_DIALOGS_JS = """
(function(){
  function visible(el){
    var r=el.getBoundingClientRect();
    var s=window.getComputedStyle(el);
    return r.width>1&&r.height>1&&s.display!=='none'&&s.visibility!=='hidden'&&s.opacity!=='0';
  }
  var containers=Array.prototype.slice.call(document.querySelectorAll(
    '.modal,.modal-dialog,[role="dialog"],.dialog,.popup,.ui-dialog,.k-window,.swal2-popup,#popup,#modal,.blockUI'
  ));
  var labels=['accept','i accept','agree','i agree','continue','ok','got it','close','dismiss'];
  for(var c=0;c<containers.length;c++){
    var box=containers[c];
    if(!visible(box)){continue;}
    var checks=box.querySelectorAll('input[type="checkbox"]');
    for(var i=0;i<checks.length;i++){
      var key=((checks[i].id||'')+' '+(checks[i].name||'')+' '+(checks[i].className||'')).toLowerCase();
      if(!checks[i].checked&&/accept|agree|read|conduct|acknowledge|terms/.test(key)){
        checks[i].click();
      }
    }
    var candidates=box.querySelectorAll('button,input[type="button"],input[type="submit"],a.btn,a.button,[role="button"]');
    for(var j=0;j<candidates.length;j++){
      var text=(candidates[j].innerText||candidates[j].value||'').trim().toLowerCase().replace(/\s+/g,' ');
      if(labels.indexOf(text)>=0){
        candidates[j].click();
        return;
      }
    }
  }
})();
"""

private const val COMPACT_CSS = """
html,body{background:#fff !important;}
body{padding-top:0 !important;margin-top:0 !important;}
body > header,body > nav,body > footer,
#header,#footer,#mainHeader,#mainNav,#navbar,#sidebar,#footerWrapper,
.navbar,.navbar-default,.navbar-fixed-top,.site-header,.site-footer,
.main-header,.main-footer,.masthead,.breadcrumb,.breadcrumbs,
.sidebar,.sidebar-nav,.skip-link,.skip-to-content,
.mobile-header,.mobile-menu,.menu-toggle,.hamburger,
#divLanguage,.language-selector,.language-switcher,
.footer-links,.copyright,.cookie-banner,#cookieConsent,.cc-window,
#ImageListDiv,#MainContent_QuickActions_ImageList_test_0_hlLink_0,
.PageTitle,#MainContent_imgProcessing,#MainContent_NestContent_Loading,
#MainContent_NestContent_MapRefresh{
  display:none !important;
}
#main,#content,.container,.container-fluid{
  padding-top:0 !important;margin-top:0 !important;
}
/* Give the live map the room it deserves on a phone. */
#ospMap{display:block !important;}
#ospMapParent{padding:0 !important;margin:0 !important;}
#wheresMyBusOsm{padding:0 !important;margin:0 !important;}
.osm-container-outer{height:58vh !important;min-height:300px !important;}
.osm-container-inner{height:100% !important;}
#bpWebMapContainer{height:100% !important;min-height:300px !important;}
#MainContent_divStudentSelector{margin-bottom:0 !important;}
"""
