package com.djvureader

import android.graphics.Rect
import android.os.IBinder
import android.os.Parcel
import android.util.Log

/** Optional Supernote ink preview. The app still owns and saves every stroke. */
class DrawPathService(private val appName: String) {
  private var binder: IBinder? = null
  private var initialized = false
  private var hasUsedInk = false

  private fun service(): IBinder? {
    binder?.takeIf { it.isBinderAlive }?.let { return it }
    binder = try {
      val manager = Class.forName("android.os.ServiceManager")
      manager.getMethod("getService", String::class.java)
        .invoke(null, "service_myservice") as? IBinder
    } catch (error: Throwable) {
      Log.w("DjvuNativeInk", "drawPath unavailable", error)
      null
    }
    return binder
  }

  private fun call(code: Int, payload: (Parcel) -> Unit): Boolean {
    val target = service() ?: return false
    val data = Parcel.obtain()
    val reply = Parcel.obtain()
    return try {
      data.writeInterfaceToken("android.demo.IMyService")
      data.writeString(appName)
      payload(data)
      target.transact(code, data, reply, 0).also {
        Log.i("DjvuNativeInk", "drawPath code=$code accepted=$it")
      }
    } catch (error: Throwable) {
      Log.w("DjvuNativeInk", "drawPath transaction $code failed", error)
      false
    } finally {
      data.recycle()
      reply.recycle()
    }
  }

  private fun area(rect: Rect, flag: Int): Boolean = call(1) { data ->
    data.writeInt(1)
    data.writeInt(rect.left)
    data.writeInt(rect.top)
    data.writeInt(rect.width())
    data.writeInt(rect.height())
    data.writeInt(flag)
  }

  fun enable(rect: Rect): Boolean {
    if (rect.isEmpty) return false
    if (!initialized) {
      if (!area(Rect(0, 0, 18888, 18888), 0)) return false
      if (!call(2) { data ->
          data.writeInt(10) // Technical pen.
          data.writeInt(600) // Six screen pixels, in hundredths.
          data.writeInt(0) // Black.
        }) return false
      initialized = true
      hasUsedInk = true
    }
    return area(rect, 1) // Only the visible DjVu page is writable.
  }

  fun disable(screen: Rect) {
    if (initialized) {
      area(screen, 0)
      initialized = false
    }
  }

  fun clear() {
    if (hasUsedInk) call(6) { it.writeInt(255) }
  }

  fun pause(screen: Rect) {
    disable(screen)
    clear()
    initialized = false
  }
}
