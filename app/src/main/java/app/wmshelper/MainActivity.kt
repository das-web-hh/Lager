package app.wmshelper

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.text.InputType
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
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
            if (items.size > 200) items.removeAt(0)
        }
    }

    var onResult: ((Int, Int, String) -> Unit)? = null

    @JavascriptInterface
    fun result(id: Int, status: Int, body: String) {
        onResult?.invoke(id, status, body)
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

    // запрос камеры от сайта (сканер): сначала разрешение Android, потом разрешение для страницы
    private var pendingPermission: PermissionRequest? = null
    private val cameraPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val req = pendingPermission
            pendingPermission = null
            if (req != null) {
                if (granted) req.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) else req.deny()
            }
        }

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
        barRow.addView(btn("Infoscan") { askInfoscan() })
        barRow.addView(btn("Заказы") { runOrders() })
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
        panelButtons.addView(btn("Raw") { show(if (lastRaw.isEmpty()) "Пусто" else lastRaw.take(60000)) })
        panelButtons.addView(btn("Очистить лог") { bridge.clear(); toast("Лог очищен") })
        panelButtons.addView(btn("Закрыть") { panel.visibility = View.GONE })
        val panelButtonsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(panelButtons)
        }
        panel.addView(panelButtonsScroll)
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
        s.mediaPlaybackRequiresUserGesture = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }

        bridge.onResult = { id, status, body ->
            runOnUiThread { pending.remove(id)?.invoke(status, body) }
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

            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val okHost = request.origin.host?.endsWith(ALLOWED_HOST_SUFFIX) == true
                    if (!okHost || PermissionRequest.RESOURCE_VIDEO_CAPTURE !in request.resources) {
                        request.deny()
                        return@runOnUiThread
                    }
                    if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA)
                        == PackageManager.PERMISSION_GRANTED
                    ) {
                        request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
                    } else {
                        pendingPermission = request
                        cameraPermLauncher.launch(Manifest.permission.CAMERA)
                    }
                }
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

    // ---------------- запросы к API сайта (через страницу, с её сессией) ----------------

    private val pending = HashMap<Int, (Int, String) -> Unit>()
    private var nextId = 1
    private var lastRaw = ""

    private fun api(method: String, path: String, body: String?, cb: (Int, String) -> Unit) {
        val id = nextId++
        pending[id] = cb
        val js = "(window.__wmsCall ? window.__wmsCall(" + id + "," + JSONObject.quote(method) + "," +
            JSONObject.quote(path) + "," + (if (body == null) "null" else JSONObject.quote(body)) + ")" +
            " : (window.WmsBridge && window.WmsBridge.result(" + id + ",-1,'hook')))"
        web.evaluateJavascript(js, null)
        web.postDelayed({ pending.remove(id)?.invoke(-2, "") }, 15000)
    }

    private fun apiError(status: Int, text: String): String = when (status) {
        -2 -> "Нет ответа за 15 секунд. Проверьте интернет."
        -1 -> "Страница сайта не готова. Нажмите «Главная», подождите 5 секунд и повторите."
        0 -> "Ошибка сети."
        401, 403 -> "Нет доступа (" + status + "). Нажмите «Главная», подождите 5 секунд (нужна сессия) и повторите."
        else -> "Ошибка " + status + ": " + text.take(300)
    }

    private fun fmtDate(s: String, withTime: Boolean = false): String {
        if (s.isEmpty()) return ""
        return try {
            val clean = s.replace(Regex("\\.\\d+Z$"), "Z")
            val inp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            val d = inp.parse(clean)!!
            SimpleDateFormat(if (withTime) "dd.MM.yyyy HH:mm" else "dd.MM.yyyy", Locale.GERMANY)
                .apply { timeZone = TimeZone.getTimeZone("Europe/Berlin") }.format(d)
        } catch (e: Exception) {
            s
        }
    }

    private fun num(d: Double): String = if (d == Math.floor(d)) d.toLong().toString() else d.toString()

    // ---- Infoscan ----

    private fun askInfoscan() {
        val input = EditText(this).apply {
            hint = "Штрихкод / номер LE"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("Infoscan")
            .setView(input)
            .setPositiveButton("Найти") { _, _ ->
                val c = input.text.toString().trim()
                if (c.isNotEmpty()) runInfoscan(c)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun runInfoscan(code: String) {
        show("Ищу: " + code + " ...")
        val body = JSONObject()
            .put("Barcode", code)
            .put("BarcodeConfigurationName", JSONObject.NULL)
            .put("IncludeAllBarcodeInfos", false)
            .toString()
        api(
            "PUT",
            "/server/api/Process/executeAction?actionKey=Warehouse.Forms.InfoScan.LayoutItems.InfoScan.Actions.GetEntitiesFromBarcode",
            body
        ) { st, txt ->
            if (st != 200) {
                show(apiError(st, txt))
            } else {
                lastRaw = txt
                try {
                    val ents = JSONObject(txt).optJSONObject("Result")?.optJSONArray("Entities")
                    if (ents == null || ents.length() == 0) {
                        show("Ничего не найдено по коду: " + code)
                    } else {
                        val sb = StringBuilder()
                        val unitIds = ArrayList<Long>()
                        for (i in 0 until ents.length()) {
                            val e = ents.getJSONObject(i)
                            sb.append(describe(e)).append("\n\n")
                            if (e.optString("EntityType").endsWith("StorageUnit") && !e.isNull("EquipmentTypeId")) {
                                unitIds.add(e.optLong("Id"))
                            }
                        }
                        show(sb.toString())
                        unitIds.forEach { loadContent(it, sb) }
                    }
                } catch (ex: Exception) {
                    show("Не удалось разобрать ответ: " + ex.message + "\n\n" + txt.take(1500))
                }
            }
        }
    }

    private fun describe(e: JSONObject): String {
        val type = e.optString("EntityType").substringAfterLast('.')
        val calc = e.optJSONObject("Calculated")
        val sb = StringBuilder("▪ ").append(type)
        val bc = e.optString("Barcode")
        if (bc.isNotEmpty()) sb.append("  ").append(bc)
        val nm = listOf(e.optString("Number"), e.optString("Name"), e.optString("Description"))
            .filter { it.isNotEmpty() }.joinToString(" · ")
        if (nm.isNotEmpty()) sb.append("\n  ").append(nm)
        val et = calc?.optJSONObject("EquipmentType")
        if (et != null) {
            val t = et.optString("Description").ifEmpty { et.optString("Name") }
            sb.append("\n  Тип: ").append(t)
        }
        val parents = calc?.optJSONObject("Topology")?.optJSONArray("Parents")
        if (parents != null && parents.length() > 0) {
            val names = ArrayList<String>()
            for (i in 0 until parents.length()) {
                val n = parents.getJSONObject(i).optString("Name")
                if (n.isNotEmpty()) names.add(n)
            }
            sb.append("\n  Место: ").append(names.joinToString(" > "))
        }
        val gi = fmtDate(calc?.optString("GoodsInDate") ?: "", true)
        if (gi.isNotEmpty()) sb.append("\n  Приход: ").append(gi)
        return sb.toString()
    }

    private fun loadContent(unitId: Long, sb: StringBuilder) {
        val body = JSONObject()
            .put("ConsiderAllBlockingKindsForCalculatedQuantities", true)
            .put("Id", unitId)
            .put(
                "SearchRequest", JSONObject()
                    .put("SortConfigurationKey", JSONObject.NULL)
                    .put("InvertSorting", false)
                    .put("CancelSearchRequestIfAbortedByClient", true)
                    .put("Skip", 0)
                    .put("Top", 50)
            ).toString()
        api(
            "PUT",
            "/server/api/Process/executeAction?actionKey=Equipment.Forms.EquipmentDetails.LayoutItems.EquipmentContent.Actions.Search",
            body
        ) { st, txt ->
            if (st != 200) {
                sb.append("Содержимое: ").append(apiError(st, txt))
                show(sb.toString())
            } else {
                lastRaw = txt
                try {
                    val pr = JSONObject(txt).optJSONObject("PageResult")
                    val items = pr?.optJSONArray("Items")
                    sb.append("Содержимое (").append(pr?.optInt("Total") ?: 0).append("):\n\n")
                    if (items != null) {
                        for (i in 0 until items.length()) {
                            sb.append(describeQuant(items.getJSONObject(i))).append("\n\n")
                        }
                    }
                    show(sb.toString())
                } catch (ex: Exception) {
                    sb.append("Не удалось разобрать содержимое: ").append(ex.message)
                    show(sb.toString())
                }
            }
        }
    }

    private fun describeQuant(q: JSONObject): String {
        val art = q.optJSONObject("Calculated")?.optJSONObject("Article")
        val sb = StringBuilder("• ")
        if (art != null) {
            val title = listOf(art.optString("Number"), art.optString("Name"))
                .filter { it.isNotEmpty() }.joinToString(" · ")
            sb.append(title.ifEmpty { "артикул " + art.optString("Id") })
            val desc = art.optString("Description")
            if (desc.isNotEmpty()) sb.append("\n   ").append(desc)
            val gtin = art.optString("GTIN")
            if (gtin.isNotEmpty()) sb.append("\n   EAN ").append(gtin)
        } else {
            sb.append("(без артикула)")
        }
        val parts = ArrayList<String>()
        if (!q.isNull("Quantity")) {
            var unit = ""
            val qu = art?.optJSONObject("Calculated")?.optJSONObject("QuantityUnit")
            if (qu != null) {
                for (k in listOf("ShortName", "Symbol", "Abbreviation", "Name")) {
                    val v = qu.optString(k)
                    if (v.isNotEmpty()) { unit = v; break }
                }
            }
            parts.add((num(q.optDouble("Quantity")) + " " + unit).trim())
        }
        val mhd = fmtDate(q.optString("ExpirationDate"))
        if (mhd.isNotEmpty()) parts.add("MHD " + mhd)
        val batch = q.optString("Batch")
        if (batch.isNotEmpty()) parts.add("Charge " + batch)
        if (parts.isNotEmpty()) sb.append("\n   ").append(parts.joinToString(" · "))
        return sb.toString()
    }

    // ---- Заказы (Bestellungen) ----

    private fun runOrders() {
        show("Загружаю заказы ...")
        val body = "{\"SearchRequest\":{\"SortConfigurationKey\":\"GoodsInOrder.SortConfigurations.Status\"," +
            "\"PropertyFilters\":[{\"Operator\":\"In\",\"Values\":[\"GoodsInOrder.Status.GoodsInOrder.Process\"," +
            "\"GoodsInOrder.Status.GoodsInOrder.Created\"],\"Key\":\"GoodsInOrder.EnumFilters.GoodsInOrder.Calculated.Status\"," +
            "\"PropertyPath\":\"Calculated.Status.Key\",\"OrPropertyPaths\":null,\"NoDbFilter\":false}]," +
            "\"InvertSorting\":false,\"CancelSearchRequestIfAbortedByClient\":true,\"Skip\":0,\"Top\":30}," +
            "\"IncludeDeactivated\":true}"
        api(
            "PUT",
            "/server/api/Process/executeAction?actionKey=GoodsInOrder.Forms.GoodsInOrderList.LayoutItems.GoodsInOrderList.Actions.Search",
            body
        ) { st, txt ->
            if (st != 200) {
                show(apiError(st, txt))
            } else {
                lastRaw = txt
                try {
                    val pr = JSONObject(txt).optJSONObject("PageResult")
                    val items = pr?.optJSONArray("Items")
                    val sb = StringBuilder("Заказы на приход: показано ")
                        .append(items?.length() ?: 0).append(" из ").append(pr?.optInt("Total") ?: 0).append("\n\n")
                    if (items != null) {
                        for (i in 0 until items.length()) {
                            val o = items.getJSONObject(i)
                            val calc = o.optJSONObject("Calculated")
                            val stRaw = calc?.opt("Status")
                            val stKey = when (stRaw) {
                                is JSONObject -> stRaw.optString("Key")
                                is String -> stRaw
                                else -> ""
                            }.substringAfterLast('.')
                            val stRu = when (stKey) {
                                "Process" -> "в работе"
                                "Created" -> "создан"
                                else -> stKey
                            }
                            val supplier = o.optJSONObject("Supplier")?.optString("Name1") ?: ""
                            val pc = if (o.has("PositionCount")) o.optInt("PositionCount", -1) else (calc?.optInt("PositionCount", -1) ?: -1)
                            sb.append("• ").append(o.optString("Number")).append(" · ").append(supplier).append("\n   ")
                            sb.append("план ").append(fmtDate(o.optString("PlannedGoodsInDate"), true))
                            if (stRu.isNotEmpty()) sb.append(" · ").append(stRu)
                            if (pc >= 0) sb.append(" · поз. ").append(pc)
                            sb.append("\n\n")
                        }
                    }
                    show(sb.toString())
                } catch (ex: Exception) {
                    show("Не удалось разобрать ответ: " + ex.message + "\n\n" + txt.take(1500))
                }
            }
        }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {

        /** Перехват fetch и XMLHttpRequest. Тела запросов входа/токенов не записываются. */
        private val HOOK_JS = """
(function(){
  if (window.__wmsHook) return;
  window.__wmsHook = true;
  var cap = window.__wmsHdr = {};
  function capture(k, v){ try { if (/^(x-csrf|x-requested-with)/i.test(k)) cap[k] = v; } catch (e) {} }
  function hc(h){
    try {
      if (!h) return;
      if (Array.isArray(h)) { h.forEach(function(p){ capture(p[0], p[1]); }); }
      else if (typeof h.forEach === 'function') { h.forEach(function(v, k){ capture(k, v); }); }
      else { Object.keys(h).forEach(function(k){ capture(k, h[k]); }); }
    } catch (e) {}
  }
  window.__wmsCall = function(id, method, url, body){
    try {
      var x = new XMLHttpRequest();
      x.open(method, url, true);
      x.setRequestHeader('Content-Type', 'application/json');
      x.setRequestHeader('Accept', 'application/json, text/plain, */*');
      for (var k in cap) { x.setRequestHeader(k, cap[k]); }
      x.onload = function(){ window.WmsBridge.result(id, x.status, x.responseText || ''); };
      x.onerror = function(){ window.WmsBridge.result(id, 0, ''); };
      x.send(body);
    } catch (e) { window.WmsBridge.result(id, -1, String(e)); }
  };
  var sens = /login|logon|auth|token|password|signin|session/i;
  var noise = /GetStatisticsUpdate|GetDashboardMessage|GetSystemMessage|\.(js|css|png|jpg|gif|svg|ico|woff2?|ttf|map)(\?|$)|coglas-cloud\.com/i;
  function clip(s, n){ if (s == null) return ''; s = String(s).replace(/\s+/g,' '); return s.length > n ? s.slice(0, n) + '...' : s; }
  function mask(u){ return String(u).replace(/(access_token|token|password|secret|api_key)=[^&]*/ig, function(m, a){ return a + '=***'; }); }
  function hn(h){
    try {
      if (!h) return '';
      if (Array.isArray(h)) return h.map(function(p){ return p[0]; }).join(',');
      if (typeof h.forEach === 'function') { var a = []; h.forEach(function(v, k){ a.push(k); }); return a.join(','); }
      return Object.keys(h).join(',');
    } catch (e) { return ''; }
  }
  function send(method, url, status, req, res, hdrs){
    try {
      if (noise.test(url)) return;
      var hide = sens.test(url);
      var line = new Date().toTimeString().slice(0,8) + ' ' + method + ' ' + status + ' ' + mask(url)
        + (hdrs ? ' | HDR: ' + hdrs : '')
        + (req && !hide ? ' | REQ: ' + clip(req, 1500) : '')
        + (res && !hide ? ' | RES: ' + clip(res, 1500) : '');
      if (window.WmsBridge) window.WmsBridge.log(line);
    } catch (e) {}
  }
  var of = window.fetch;
  if (of) {
    window.fetch = function(input, init){
      var url = (typeof input === 'string') ? input : ((input && input.url) || '');
      var method = (init && init.method) || (input && input.method) || 'GET';
      var body = (init && typeof init.body === 'string') ? init.body : '';
      var hd = hn(init && init.headers);
      hc(init && init.headers);
      return of.apply(this, arguments).then(function(r){
        try {
          r.clone().text().then(function(t){ send(method, url, r.status, body, t, hd); })
            .catch(function(){ send(method, url, r.status, body, '', hd); });
        } catch (e) {}
        return r;
      });
    };
  }
  var xo = XMLHttpRequest.prototype.open, xs = XMLHttpRequest.prototype.send, xh = XMLHttpRequest.prototype.setRequestHeader;
  XMLHttpRequest.prototype.open = function(m, u){ this.__m = m; this.__u = u; return xo.apply(this, arguments); };
  XMLHttpRequest.prototype.setRequestHeader = function(k, v){ (this.__h = this.__h || []).push(k); capture(k, v); return xh.apply(this, arguments); };
  XMLHttpRequest.prototype.send = function(b){
    var x = this; var body = (typeof b === 'string') ? b : '';
    x.addEventListener('loadend', function(){
      var t = '';
      try { if (x.responseType === '' || x.responseType === 'text') t = x.responseText; } catch (e) {}
      send(x.__m || 'GET', x.__u || '', x.status, body, t, (x.__h || []).join(','));
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
