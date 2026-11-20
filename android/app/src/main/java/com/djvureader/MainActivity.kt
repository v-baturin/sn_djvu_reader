package com.djvureader

import android.content.Intent
import android.os.Build
import android.graphics.Rect
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import com.facebook.react.ReactActivity
import com.facebook.react.ReactActivityDelegate
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint.fabricEnabled
import com.facebook.react.defaults.DefaultReactActivityDelegate

class MainActivity : ReactActivity() {
  private var penPointerId = -1
  private var lastPenMoveSentAt = 0L
  private val nativeInk by lazy { DrawPathService(packageName) }
  private var nativeInkActive = false
  private var penUsesNativeInk = false
  private var penRegion = Rect()
  private val penTrace = ArrayList<FloatArray>()

  private fun screenRect(): Rect = Rect(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)

  fun setNativeInkRegion(left: Double, top: Double, right: Double, bottom: Double, enabled: Boolean) {
    val web = findWebView(window.decorView)
    if (!enabled || web == null || web.width == 0 || web.height == 0) {
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
    bounds.intersect(screenRect())
    nativeInkActive = nativeInk.enable(bounds)
    penRegion = if (nativeInkActive) bounds else Rect()
    Log.i("DjvuNativeInk", "region active=$nativeInkActive bounds=$penRegion")
  }

  fun clearNativeInk() {
    nativeInk.clear()
  }

  override fun onPause() {
    nativeInkActive = false
    penRegion.setEmpty()
    nativeInk.pause(screenRect())
    super.onPause()
  }

  private fun findWebView(view: View): WebView? {
    if (view is WebView) return view
    if (view is ViewGroup) for (index in 0 until view.childCount) {
      val found = findWebView(view.getChildAt(index))
      if (found != null) return found
    }
    return null
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
    val web = findWebView(window.decorView) ?: return
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
    val web = findWebView(window.decorView) ?: return
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
  }

  /**
   * Returns the name of the main component registered from JavaScript. This is used to schedule
   * rendering of the component.
   */
  override fun getMainComponentName(): String = "DjvuReader"

  /**
   * Returns the instance of the [ReactActivityDelegate]. We use [DefaultReactActivityDelegate]
   * which allows you to enable New Architecture with a single boolean flags [fabricEnabled]
   */
  override fun createReactActivityDelegate(): ReactActivityDelegate =
      DefaultReactActivityDelegate(this, mainComponentName, fabricEnabled)
}
