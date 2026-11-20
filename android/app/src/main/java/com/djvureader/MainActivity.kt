package com.djvureader

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors
import org.json.JSONObject

class MainActivity : Activity() {
  private data class Book(val path: String, val name: String, val size: Long, val ink: String, val view: String)

  private val io = Executors.newSingleThreadExecutor()
  private val storage by lazy { DjvuStorage(this) }
  private val nativeInk by lazy { DrawPathService(packageName) }
  private val readerHtml by lazy {
    val template = assets.open("reader.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
    val decoder = assets.open("djvu.js").bufferedReader(Charsets.UTF_8).use { it.readText() }
    require("/* DJVU_DECODER */" in template && !decoder.contains("</script", true)) { "Invalid reader assets" }
    template.replace("/* DJVU_DECODER */", decoder)
  }
  private lateinit var header: LinearLayout
  private lateinit var openButton: Button
  private lateinit var status: TextView
  private lateinit var content: FrameLayout
  private lateinit var browser: LinearLayout
  private lateinit var folderTitle: TextView
  private lateinit var folderRows: LinearLayout
  private lateinit var empty: TextView
  private var webView: WebView? = null
  @Volatile private var book: Book? = null
  @Volatile private var generation = 0
  private var folderGeneration = 0
  private var folderHistory = listOf("")
  private var browserOpen = false
  private var toolbarsVisible = true
  private var penPointerId = -1
  private var lastPenMoveSentAt = 0L
  private var nativeInkActive = false
  private var penUsesNativeInk = false
  private var penRegion = Rect()
  private val penTrace = ArrayList<FloatArray>()

  private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.statusBarColor = Color.WHITE
    window.navigationBarColor = Color.WHITE
    window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    createUi()
    val path = intentFile(intent) ?: storage.lastFile()
    if (path != null) openFile(path) else showEmpty()
  }

  private fun createUi() {
    val root = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setBackgroundColor(Color.WHITE)
    }
    header = LinearLayout(this).apply {
      gravity = Gravity.CENTER_VERTICAL
      setPadding(dp(8), 0, dp(8), 0)
    }
    openButton = Button(this).apply {
      text = "Open DjVu"
      isAllCaps = false
      setOnClickListener { openBrowser() }
    }
    status = TextView(this).apply {
      text = "Choose a DjVu file to read."
      textSize = 14f
      setTextColor(Color.DKGRAY)
      maxLines = 1
      ellipsize = android.text.TextUtils.TruncateAt.END
      setPadding(dp(10), 0, 0, 0)
    }
    header.addView(openButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56)))
    header.addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)))
    content = FrameLayout(this)
    root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    empty = TextView(this).apply {
      text = "DjVu Reader\nOpen a file to start reading."
      textSize = 22f
      setTextColor(Color.BLACK)
      gravity = Gravity.CENTER
    }
    content.addView(empty, FrameLayout.LayoutParams(-1, -1))
    createBrowser()
    setContentView(root)
  }

  private fun createBrowser() {
    browser = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setBackgroundColor(Color.WHITE)
      visibility = View.GONE
    }
    val browserHeader = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
    val back = Button(this).apply {
      text = "Back"
      isAllCaps = false
      setOnClickListener { backFromBrowser() }
    }
    folderTitle = TextView(this).apply {
      textSize = 18f
      setTextColor(Color.BLACK)
      maxLines = 1
      ellipsize = android.text.TextUtils.TruncateAt.END
      setPadding(dp(10), 0, 0, 0)
    }
    browserHeader.addView(back, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56)))
    browserHeader.addView(folderTitle, LinearLayout.LayoutParams(0, -2, 1f))
    browser.addView(browserHeader, LinearLayout.LayoutParams(-1, dp(64)))
    folderRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val scroll = ScrollView(this).apply { addView(folderRows) }
    browser.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
    content.addView(browser, FrameLayout.LayoutParams(-1, -1))
  }

  private fun showEmpty() {
    empty.visibility = View.VISIBLE
    status.text = "Choose a DjVu file to read."
  }

  private fun updateHeader() {
    header.visibility = if (browserOpen || toolbarsVisible) View.VISIBLE else View.GONE
  }

  private fun openBrowser() {
    setNativeInkRegion(0.0, 0.0, 0.0, 0.0, false)
    clearNativeInk()
    browserOpen = true
    webView?.onPause()
    webView?.visibility = View.GONE
    empty.visibility = View.GONE
    browser.visibility = View.VISIBLE
    updateHeader()
    if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 7001)
    } else {
      loadFolder("", listOf(""))
    }
  }

  override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    if (requestCode == 7001 && browserOpen) {
      if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) loadFolder("", listOf(""))
      else showFolderError("Allow file access to browse DjVu books.")
    }
  }

  private fun showFolderError(message: String) {
    folderRows.removeAllViews()
    folderRows.addView(TextView(this).apply {
      text = message
      textSize = 18f
      setTextColor(Color.BLACK)
      setPadding(dp(16), dp(16), dp(16), dp(16))
    })
  }

  private fun loadFolder(path: String, history: List<String>) {
    val request = ++folderGeneration
    folderTitle.text = if (path.isBlank()) "Folders" else java.io.File(path).name
    showFolderError("Loading…")
    io.execute {
      try {
        val entries = storage.listFolder(path)
        runOnUiThread {
          if (!browserOpen || request != folderGeneration) return@runOnUiThread
          folderHistory = history
          folderRows.removeAllViews()
          if (entries.isEmpty()) showFolderError("No DjVu files in this folder.")
          entries.forEach { entry ->
            val row = TextView(this).apply {
              text = (if (entry.directory) "Folder  " else "DjVu  ") + entry.name
              textSize = 18f
              setTextColor(Color.BLACK)
              gravity = Gravity.CENTER_VERTICAL
              minHeight = dp(60)
              setPadding(dp(16), dp(12), dp(16), dp(12))
              setOnClickListener {
                if (entry.directory) loadFolder(entry.path, folderHistory + entry.path)
                else openFile(entry.path)
              }
            }
            folderRows.addView(row, LinearLayout.LayoutParams(-1, -2))
            folderRows.addView(View(this).apply { setBackgroundColor(Color.LTGRAY) }, LinearLayout.LayoutParams(-1, dp(1)))
          }
        }
      } catch (error: Exception) {
        runOnUiThread { if (request == folderGeneration) showFolderError(error.message ?: error.toString()) }
      }
    }
  }

  private fun backFromBrowser() {
    if (folderHistory.size > 1) {
      val history = folderHistory.dropLast(1)
      loadFolder(history.last(), history)
    } else {
      browserOpen = false
      ++folderGeneration
      browser.visibility = View.GONE
      webView?.visibility = View.VISIBLE
      webView?.onResume()
      if (webView == null) empty.visibility = View.VISIBLE
      updateHeader()
      evaluate("window.refreshPenRegion && window.refreshPenRegion()")
    }
  }

  @Deprecated("The Android back callback is retained for API 27 support")
  override fun onBackPressed() {
    if (browserOpen) backFromBrowser() else super.onBackPressed()
  }

  private fun intentFile(incoming: Intent?): String? {
    val explicit = incoming?.getStringExtra("djvuPath")
    if (explicit != null) return explicit
    if (incoming?.action != Intent.ACTION_VIEW) return null
    val uri = incoming.data ?: return null
    if (uri.scheme == "content") {
      try {
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
      } catch (_: SecurityException) { }
    }
    return if (uri.scheme == "file") uri.path else uri.toString()
  }

  private fun openFile(path: String) {
    val request = ++generation
    status.text = "Opening…"
    io.execute {
      try {
        val size = storage.checkedLength(path)
        val name = storage.name(path)
        val ink = storage.readInk(path)
        val view = storage.readViewState(path)
        storage.rememberFile(path)
        val next = Book(path, name, size, ink, view)
        runOnUiThread { if (request == generation) showBook(next, request) }
      } catch (error: Exception) {
        runOnUiThread {
          if (request == generation) {
            status.text = error.message ?: error.toString()
            if (browserOpen) showFolderError(error.message ?: error.toString())
          }
        }
      }
    }
  }

  private fun showBook(next: Book, request: Int) {
    setNativeInkRegion(0.0, 0.0, 0.0, 0.0, false)
    clearNativeInk()
    webView?.let { content.removeView(it); it.destroy() }
    book = next
    browserOpen = false
    browser.visibility = View.GONE
    empty.visibility = View.GONE
    toolbarsVisible = true
    updateHeader()
    openButton.text = "Other file"
    status.text = "Opening ${next.name}…"
    val web = WebView(this)
    web.setBackgroundColor(Color.WHITE)
    web.settings.javaScriptEnabled = true
    web.settings.domStorageEnabled = false
    web.settings.allowFileAccess = false
    web.settings.allowContentAccess = false
    web.settings.blockNetworkLoads = true
    web.webViewClient = object : WebViewClient() {
      override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = true
      override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean = true
    }
    web.webChromeClient = object : WebChromeClient() {
      override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult): Boolean {
        AlertDialog.Builder(this@MainActivity).setMessage(message)
          .setPositiveButton("OK") { _, _ -> result.confirm() }
          .setNegativeButton("Cancel") { _, _ -> result.cancel() }
          .setOnCancelListener { result.cancel() }.show()
        return true
      }
    }
    web.addJavascriptInterface(ReaderBridge(request), "AndroidBridge")
    webView = web
    content.addView(web, 0, FrameLayout.LayoutParams(-1, -1))
    web.loadDataWithBaseURL("https://djvu.local/", readerHtml, "text/html", "UTF-8", null)
  }

  private inner class ReaderBridge(private val request: Int) {
    @JavascriptInterface
    fun postMessage(json: String) {
      try {
        handleMessage(request, JSONObject(json))
      } catch (error: Exception) {
        Log.e("DjvuReader", "Invalid reader message", error)
      }
    }
  }

  private fun handleMessage(request: Int, data: JSONObject) {
    if (request != generation) return
    when (data.optString("type")) {
      "ready" -> runOnUiThread {
        if (request != generation) return@runOnUiThread
        val current = book ?: return@runOnUiThread
        evaluate("window.startFile(${current.size},${JSONObject.quote(current.name)},${JSONObject.quote(current.ink)},${JSONObject.quote(current.view)})")
      }
      "read" -> {
        val path = book?.path ?: return
        val offset = data.optLong("offset", -1)
        io.execute {
          try {
            val chunk = storage.readChunk(path, offset, 262144)
            runOnUiThread { if (request == generation) evaluate("window.receiveChunk(${JSONObject.quote(chunk)})") }
          } catch (error: Exception) { readerError(request, error) }
        }
      }
      "saveInk" -> {
        val path = book?.path ?: return
        val ink = data.optString("ink")
        io.execute { try { storage.writeInk(path, ink) } catch (error: Exception) { readerError(request, error) } }
      }
      "saveViewState" -> {
        val path = book?.path ?: return
        val state = data.optJSONObject("state")?.toString() ?: return
        io.execute { try { storage.writeViewState(path, state) } catch (error: Exception) { readerError(request, error) } }
      }
      "error" -> runOnUiThread { if (request == generation) status.text = data.optString("message") }
      "page" -> runOnUiThread {
        if (request == generation) status.text = "${book?.name ?: "DjVu"} · ${data.optInt("page")}/${data.optInt("count")}"
      }
      "toolbars" -> runOnUiThread {
        if (request == generation) {
          toolbarsVisible = data.optBoolean("visible", true)
          updateHeader()
        }
      }
      "penRegion" -> runOnUiThread {
        if (request != generation) return@runOnUiThread
        val bounds = data.optJSONArray("bounds") ?: return@runOnUiThread
        if (bounds.length() == 4) setNativeInkRegion(bounds.optDouble(0), bounds.optDouble(1),
          bounds.optDouble(2), bounds.optDouble(3), data.optBoolean("enabled"))
      }
      "clearNativeInk" -> runOnUiThread { if (request == generation) clearNativeInk() }
      "renderTime" -> Log.i("DjvuReader", "page ${data.optInt("page")}: ${data.optInt("ms")} ms cache=${data.optBoolean("fromCache")}")
      "prefetchStatus" -> Log.i("DjvuReader", "prefetch: ${data.optString("message")}")
    }
  }

  private fun readerError(request: Int, error: Exception) {
    Log.e("DjvuReader", "Reader operation failed", error)
    runOnUiThread {
      if (request == generation) {
        status.text = error.message ?: error.toString()
        evaluate("window.readerError(${JSONObject.quote(error.message ?: error.toString())})")
      }
    }
  }

  private fun evaluate(script: String) {
    webView?.evaluateJavascript("$script; true;", null)
  }

  private fun screenRect(): Rect = Rect(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)

  fun setNativeInkRegion(left: Double, top: Double, right: Double, bottom: Double, enabled: Boolean) {
    val web = webView
    if (!enabled || browserOpen || web == null || web.width == 0 || web.height == 0) {
      nativeInkActive = false
      penRegion.setEmpty()
      nativeInk.disable(screenRect())
      return
    }
    val origin = IntArray(2)
    web.getLocationOnScreen(origin)
    val bounds = Rect(
      origin[0] + (left * web.width).toInt(),
      origin[1] + (top * web.height).toInt(),
      origin[0] + (right * web.width).toInt(),
      origin[1] + (bottom * web.height).toInt()
    )
    if (!bounds.intersect(screenRect())) bounds.setEmpty()
    nativeInkActive = nativeInk.enable(bounds)
    penRegion = if (nativeInkActive) bounds else Rect()
    Log.i("DjvuNativeInk", "region active=$nativeInkActive bounds=$penRegion")
  }

  fun clearNativeInk() { nativeInk.clear() }

  override fun onResume() {
    super.onResume()
    if (!browserOpen) {
      webView?.onResume()
      evaluate("window.refreshPenRegion && window.refreshPenRegion()")
    }
  }

  override fun onPause() {
    nativeInkActive = false
    penRegion.setEmpty()
    nativeInk.pause(screenRect())
    webView?.onPause()
    super.onPause()
  }

  override fun onDestroy() {
    ++generation
    ++folderGeneration
    webView?.let { content.removeView(it); it.destroy() }
    io.shutdown()
    super.onDestroy()
  }

  private fun isPen(event: MotionEvent, index: Int): Boolean {
    val tool = event.getToolType(index)
    return tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER ||
      (tool == MotionEvent.TOOL_TYPE_UNKNOWN && event.isFromSource(InputDevice.SOURCE_STYLUS))
  }

  private fun rawX(event: MotionEvent, index: Int): Float =
    if (Build.VERSION.SDK_INT >= 29) event.getRawX(index)
    else event.rawX + event.getX(index) - event.x

  private fun rawY(event: MotionEvent, index: Int): Float =
    if (Build.VERSION.SDK_INT >= 29) event.getRawY(index)
    else event.rawY + event.getY(index) - event.y

  private fun appendPenPoint(event: MotionEvent, index: Int, history: Int = -1) {
    val web = webView ?: return
    if (web.width == 0 || web.height == 0) return
    val origin = IntArray(2)
    web.getLocationOnScreen(origin)
    val x = if (history >= 0) event.getHistoricalX(index, history) + rawX(event, index) - event.getX(index)
      else rawX(event, index)
    val y = if (history >= 0) event.getHistoricalY(index, history) + rawY(event, index) - event.getY(index)
      else rawY(event, index)
    val point = floatArrayOf((x - origin[0]) / web.width, (y - origin[1]) / web.height)
    val previous = penTrace.lastOrNull()
    if (previous == null || Math.hypot(((point[0] - previous[0]) * web.width).toDouble(),
        ((point[1] - previous[1]) * web.height).toDouble()) >= 1.0) penTrace.add(point)
  }

  private fun sendPen(event: MotionEvent, index: Int, action: String) {
    val web = webView ?: return
    if (web.width == 0 || web.height == 0) return
    val location = IntArray(2)
    web.getLocationOnScreen(location)
    val x = (rawX(event, index) - location[0]) / web.width
    val y = (rawY(event, index) - location[1]) / web.height
    val trace = if (action == "up" && penUsesNativeInk) {
      penTrace.joinToString(prefix = "[", postfix = "]") { "[${it[0]},${it[1]}]" }
    } else "null"
    web.evaluateJavascript("window.nativePenEvent && window.nativePenEvent('$action',$x,$y,$penUsesNativeInk,$trace);", null)
  }

  override fun dispatchTouchEvent(event: MotionEvent): Boolean {
    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
        val index = event.actionIndex
        if (isPen(event, index) && penPointerId == -1) {
          penPointerId = event.getPointerId(index)
          lastPenMoveSentAt = event.eventTime
          penUsesNativeInk = nativeInkActive && penRegion.contains(rawX(event, index).toInt(), rawY(event, index).toInt())
          Log.i("DjvuNativeInk", "pen native=$penUsesNativeInk at ${rawX(event, index)},${rawY(event, index)}")
          penTrace.clear()
          if (penUsesNativeInk) appendPenPoint(event, index)
          sendPen(event, index, "down")
        }
      }
      MotionEvent.ACTION_MOVE -> {
        if (penPointerId != -1) {
          val index = event.findPointerIndex(penPointerId)
          if (index >= 0) {
            if (penUsesNativeInk) {
              for (history in 0 until event.historySize) appendPenPoint(event, index, history)
              appendPenPoint(event, index)
            } else if (event.eventTime - lastPenMoveSentAt >= 32L) {
              lastPenMoveSentAt = event.eventTime
              sendPen(event, index, "move")
            }
          }
        }
      }
      MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
        val index = event.actionIndex
        if (event.getPointerId(index) == penPointerId) {
          if (penUsesNativeInk) appendPenPoint(event, index)
          sendPen(event, index, "up")
          penPointerId = -1
          penUsesNativeInk = false
          penTrace.clear()
        }
      }
      MotionEvent.ACTION_CANCEL -> {
        if (penPointerId != -1) {
          val index = event.findPointerIndex(penPointerId)
          if (index >= 0) sendPen(event, index, "cancel")
          penPointerId = -1
          penUsesNativeInk = false
          penTrace.clear()
          nativeInk.clear()
        }
      }
    }
    return super.dispatchTouchEvent(event)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    intentFile(intent)?.let { openFile(it) }
  }
}
