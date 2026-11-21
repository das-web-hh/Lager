package app.wmshelper

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import org.json.JSONTokener

private const val START_URL = "https://stroeh.coglas.com/"
private const val ALLOWED_HOST_SUFFIX = "coglas.com"

/** Сюда JS-хук присылает строки журнала сетевых запросов. */
class Bridge {
    private val items = ArrayList<String>()

    @JavascriptInterface
    fun log(line: String) {
        synchronized(items) {
            items.add(line)
            if (items.size > 500) items.removeAt(0)
        }
    }

    fun dump(): String = synchronized(items) { items.joinToString("\n") }
    fun size(): Int = synchronized(items) { items.size }
    fun clear() = synchronized(items) { items.clear() }
}

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var progress: ProgressBar
    private lateinit var panel: LinearLayout
    private lateinit var panelText: TextView
    private val bridge = Bridge()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // ---- верхняя панель кнопок ----
        val barRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(title: String, action: () -> Unit): Button = Button(this).apply {
            text = title
            isAllCaps = false
            setOnClickListener { action() }
        }
        barRow.addView(btn("←") { if (web.canGoBack()) web.goBack() })
        barRow.addView(btn("⟳") { web.reload() })
        barRow.addView(btn("Главная") { web.loadUrl(START_URL) })
        barRow.addView(btn("Разведка") { runProbe() })
        barRow.addView(btn("Текст") { runPageText() })
        barRow.addView(btn("Лог") { showLog() })
        barRow.addView(btn("Нажать") { askClick() })
        val bar = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(barRow)
        }
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)))

        // ---- WebView ----
        web = WebView(this)
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // ---- нижняя панель результатов ----
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#111111"))
            visibility = View.GONE
        }
        panelText = TextView(this).apply {
            setTextColor(Color.parseColor("#DDDDDD"))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val scroll = ScrollView(this).apply { addView(panelText) }
        panel.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val panelButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        panelButtons.addView(btn("Копировать") { copyPanel() })
        panelButtons.addView(btn("Поделиться") { sharePanel() })
        panelButtons.addView(btn("Очистить лог") { bridge.clear(); toast("Лог очищен") })
        panelButtons.addView(btn("Закрыть") { panel.visibility = View.GONE })
        panel.addView(panelButtons)
        root.addView(panel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.9f))

        setContentView(root)

        // ---- настройки WebView ----
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }

        web.addJavascriptInterface(bridge, "WmsBridge")
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(web, HOOK_JS, setOf("https://stroeh.coglas.com"))
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val host = request.url.host ?: return false
                if (host.endsWith(ALLOWED_HOST_SUFFIX)) return false
                startActivity(Intent(Intent.ACTION_VIEW, request.url))
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                // запасной вариант, если DOCUMENT_START_SCRIPT не поддерживается
                if (url != null && Uri.parse(url).host?.endsWith(ALLOWED_HOST_SUFFIX) == true) {
                    view.evaluateJavascript(HOOK_JS, null)
                }
            }
        }
        web.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })

        if (savedInstanceState == null) web.loadUrl(START_URL)
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    // ---------------- функции агента ----------------

    private fun runJs(js: String, onResult: (String) -> Unit) {
        web.evaluateJavascript(js) { raw ->
            val text = try {
                (JSONTokener(raw ?: "null").nextValue() as? String) ?: (raw ?: "")
            } catch (e: Exception) {
                raw ?: ""
            }
            onResult(text)
        }
    }

    private fun show(text: String) {
        panelText.text = text
        panel.visibility = View.VISIBLE
    }

    private fun runProbe() = runJs(PROBE_JS) { show(it) }

    private fun runPageText() = runJs("document.body.innerText.slice(0, 20000)") { show(it) }

    private fun showLog() {
        val n = bridge.size()
        show(
            if (n == 0) "Лог пуст. Походите по сайту (откройте заказ, поиск и т.д.), затем снова нажмите «Лог»."
            else "Запросов в логе: $n\n(данные входа и токены скрыты)\n\n" + bridge.dump()
        )
    }

    private fun askClick() {
        val input = EditText(this).apply { hint = "Текст кнопки, например: Lager" }
        AlertDialog.Builder(this)
            .setTitle("Нажать по тексту")
            .setView(input)
            .setPositiveButton("Нажать") { _, _ ->
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) {
                    runJs(CLICK_JS.replace("__T__", JSONObject.quote(t))) { show(it) }
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun copyPanel() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("wms", panelText.text))
        toast("Скопировано")
    }

    private fun sharePanel() {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, panelText.text.toString())
        }
        startActivity(Intent.createChooser(i, "Поделиться"))
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {

        /** Перехват fetch и XMLHttpRequest. Тела запросов входа/токенов не записываются. */
        private val HOOK_JS = """
(function(){
  if (window.__wmsHook) return;
  window.__wmsHook = true;
  var sens = /login|logon|auth|token|password|signin|session/i;
  function clip(s, n){ if (s == null) return ''; s = String(s).replace(/\s+/g,' '); return s.length > n ? s.slice(0, n) + '...' : s; }
  function send(method, url, status, req, res){
    try {
      var hide = sens.test(url);
      var line = new Date().toTimeString().slice(0,8) + ' ' + method + ' ' + status + ' ' + url
        + (req && !hide ? ' | REQ: ' + clip(req, 400) : '')
        + (res && !hide ? ' | RES: ' + clip(res, 400) : '');
      if (window.WmsBridge) window.WmsBridge.log(line);
    } catch (e) {}
  }
  var of = window.fetch;
  if (of) {
    window.fetch = function(input, init){
      var url = (typeof input === 'string') ? input : ((input && input.url) || '');
      var method = (init && init.method) || (input && input.method) || 'GET';
      var body = (init && typeof init.body === 'string') ? init.body : '';
      return of.apply(this, arguments).then(function(r){
        try {
          r.clone().text().then(function(t){ send(method, url, r.status, body, t); })
            .catch(function(){ send(method, url, r.status, body, ''); });
        } catch (e) {}
        return r;
      });
    };
  }
  var xo = XMLHttpRequest.prototype.open, xs = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open = function(m, u){ this.__m = m; this.__u = u; return xo.apply(this, arguments); };
  XMLHttpRequest.prototype.send = function(b){
    var x = this; var body = (typeof b === 'string') ? b : '';
    x.addEventListener('loadend', function(){
      var t = '';
      try { if (x.responseType === '' || x.responseType === 'text') t = x.responseText; } catch (e) {}
      send(x.__m || 'GET', x.__u || '', x.status, body, t);
    });
    return xs.apply(this, arguments);
  };
})();
"""

        /** Только чтение: список видимых кнопок, ссылок и полей страницы. */
        private val PROBE_JS = """
(function(){
  function vis(e){ var r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0; }
  var out = ['URL: ' + location.href, 'TITLE: ' + document.title, ''];
  var els = document.querySelectorAll('a,button,input,select,textarea,[role=button],[onclick],[ng-click]');
  var n = 0;
  for (var i = 0; i < els.length && n < 150; i++) {
    var e = els[i];
    if (!vis(e)) continue;
    n++;
    var t = ((e.type === 'password') ? '' : (e.innerText || e.value || '')).trim().replace(/\s+/g,' ').slice(0, 40);
    var c = (typeof e.className === 'string') ? e.className : '';
    var cls = c.split(' ').filter(Boolean).slice(0, 3).join('.');
    var href = e.getAttribute('href');
    var extra = [e.type && 'type=' + e.type, e.name && 'name=' + e.name, e.placeholder && 'ph=' + e.placeholder, href && 'href=' + href.slice(0, 60)].filter(Boolean).join(' ');
    out.push(e.tagName.toLowerCase() + (e.id ? '#' + e.id : '') + (cls ? '.' + cls : '') + ' "' + t + '" ' + extra);
  }
  out.push('', 'Показано элементов: ' + n);
  return out.join('\n');
})()
"""

        /** Нажимает элемент, чей видимый текст содержит заданную строку (берётся самый короткий). */
        private val CLICK_JS = """
(function(t){
  t = t.toLowerCase().trim();
  var all = document.querySelectorAll('a,button,[role=button],li,span,div,td,label');
  var best = null, bl = 1e9;
  for (var i = 0; i < all.length; i++) {
    var e = all[i], r = e.getBoundingClientRect();
    if (r.width <= 0 || r.height <= 0) continue;
    var x = (e.innerText || '').trim().toLowerCase();
    if (x.indexOf(t) < 0) continue;
    if (x.length < bl) { bl = x.length; best = e; }
  }
  if (!best) return 'Не найдено: ' + t;
  best.scrollIntoView({block: 'center'});
  best.click();
  return 'Нажато: <' + best.tagName.toLowerCase() + '> "' + (best.innerText || '').trim().slice(0, 60) + '"';
})(__T__)
"""
    }
}
