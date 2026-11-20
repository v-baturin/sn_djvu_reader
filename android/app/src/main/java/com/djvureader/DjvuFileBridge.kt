package com.djvureader

import android.util.Base64
import android.os.Environment
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.BaseActivityEventListener
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.Executors
import org.json.JSONObject

class DjvuFileBridge(context: ReactApplicationContext) : ReactContextBaseJavaModule(context) {
  private val executor = Executors.newSingleThreadExecutor()
  private val preferences = context.getSharedPreferences("djvu-reader", Context.MODE_PRIVATE)
  private val pickRequest = 6417
  private var pickPromise: Promise? = null
  private val maxFileSize = 268435456L
  private val libraryFolders = listOf("Document", "INBOX", "MyStyle", "EXPORT", "Note", "SCREENSHOT")

  override fun getName(): String = "DjvuFileBridge"

  @ReactMethod
  fun setNativeInkRegion(left: Double, top: Double, right: Double, bottom: Double, enabled: Boolean) {
    val activity = currentActivity as? MainActivity ?: return
    activity.runOnUiThread { activity.setNativeInkRegion(left, top, right, bottom, enabled) }
  }

  @ReactMethod
  fun clearNativeInk() {
    val activity = currentActivity as? MainActivity ?: return
    activity.runOnUiThread { activity.clearNativeInk() }
  }

  override fun getConstants(): MutableMap<String, Any> = mutableMapOf(
    "standalone" to (android.os.Process.myUid() != android.os.Process.SYSTEM_UID)
  )

  init {
    context.addActivityEventListener(object : BaseActivityEventListener() {
      override fun onActivityResult(activity: Activity, requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != pickRequest) return
        val pending = pickPromise ?: return
        pickPromise = null
        if (resultCode != Activity.RESULT_OK || data?.data == null) {
          pending.resolve(null)
          return
        }
        val uri = data.data!!
        try {
          context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
          // Some providers grant access for this session without persistence.
        }
        pending.resolve(uri.toString())
      }
    })
  }

  private fun documentName(path: String): String {
    if (!path.startsWith("content://")) return File(path).name
    val uri = Uri.parse(path)
    contextQuery(uri)?.use { cursor ->
      if (cursor.moveToFirst()) return cursor.getString(0) ?: ""
    }
    return uri.lastPathSegment ?: ""
  }

  private fun contextQuery(uri: Uri): Cursor? =
    reactApplicationContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)

  private fun checkedLength(path: String): Long {
    val name = documentName(path)
    require(name.endsWith(".djvu", true) || name.endsWith(".djv", true)) { "Choose a .djvu or .djv file" }
    val length = if (path.startsWith("content://")) {
      reactApplicationContext.contentResolver.openFileDescriptor(Uri.parse(path), "r")?.use { it.statSize }
        ?: throw IllegalStateException("The selected file is not readable")
    } else checkedFile(path).length()
    require(length in 1L..maxFileSize) { "File must be between 1 byte and 256 MB" }
    return length
  }

  private fun checkedFile(path: String): File {
    val file = File(path).canonicalFile
    require(file.name.endsWith(".djvu", true) || file.name.endsWith(".djv", true)) {
      "Only DjVu files can be read"
    }
    require(file.isFile && file.canRead()) { "The selected file is not readable" }
    require(file.length() in 1L..maxFileSize) { "File must be between 1 byte and 256 MB" }
    return file
  }

  private fun inkFile(path: String): File {
    checkedLength(path)
    val canonicalPath = if (path.startsWith("content://")) path else File(path).canonicalPath
    val digest = MessageDigest.getInstance("SHA-256").digest(canonicalPath.toByteArray(Charsets.UTF_8))
    val key = digest.joinToString("") { "%02x".format(it) }
    val folder = File(reactApplicationContext.filesDir, "djvu-reader-ink")
    require(folder.isDirectory || folder.mkdirs()) { "Cannot create ink storage" }
    return File(folder, "$key.json")
  }

  private fun viewStateKey(path: String): String {
    checkedLength(path)
    val canonicalPath = if (path.startsWith("content://")) path else File(path).canonicalPath
    val digest = MessageDigest.getInstance("SHA-256").digest(canonicalPath.toByteArray(Charsets.UTF_8))
    return "view:" + digest.joinToString("") { "%02x".format(it) }
  }

  @ReactMethod
  fun pickFile(promise: Promise) {
    if (pickPromise != null) { promise.reject("DJVU_PICK", "A file picker is already open"); return }
    val activity = currentActivity
    if (activity == null) { promise.reject("DJVU_PICK", "Reader activity is unavailable"); return }
    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
      addCategory(Intent.CATEGORY_OPENABLE)
      type = "*/*"
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    }
    try {
      pickPromise = promise
      activity.startActivityForResult(intent, pickRequest)
    } catch (error: Exception) {
      pickPromise = null
      promise.reject("DJVU_PICK", error)
    }
  }

  @ReactMethod
  fun launchCompanion(path: String, promise: Promise) {
    try {
      checkedLength(path)
      val intent = Intent(Intent.ACTION_MAIN).apply {
        setClassName("com.djvureader", "com.djvureader.MainActivity")
        addCategory(Intent.CATEGORY_LAUNCHER)
        putExtra("djvuPath", path)
      }
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      reactApplicationContext.startActivity(intent)
      promise.resolve(null)
    } catch (error: Exception) { promise.reject("DJVU_LAUNCH", error) }
  }

  @ReactMethod
  fun getLaunchFile(promise: Promise) {
    val intent = currentActivity?.intent
    val viewedFile = if (intent?.action == Intent.ACTION_VIEW) {
      val uri = intent.data
      if (uri?.scheme == "file") uri.path else uri?.toString()
    } else null
    promise.resolve(intent?.getStringExtra("djvuPath") ?: viewedFile ?: preferences.getString("lastFile", null))
  }

  @ReactMethod
  fun rememberFile(path: String, promise: Promise) {
    executor.execute {
      try {
        checkedLength(path)
        preferences.edit().putString("lastFile", path).apply()
        promise.resolve(null)
      } catch (error: Exception) { promise.reject("DJVU_REMEMBER", error) }
    }
  }

  @ReactMethod
  fun readViewState(path: String, promise: Promise) {
    executor.execute {
      try { promise.resolve(preferences.getString(viewStateKey(path), "{}")) }
      catch (error: Exception) { promise.reject("DJVU_VIEW_READ", error) }
    }
  }

  @ReactMethod
  fun writeViewState(path: String, state: String, promise: Promise) {
    executor.execute {
      try {
        require(state.toByteArray(Charsets.UTF_8).size <= 2048) { "View state is too large" }
        val parsed = JSONObject(state)
        require(parsed.optInt("page", 0) >= 1) { "Invalid page" }
        preferences.edit().putString(viewStateKey(path), state).apply()
        promise.resolve(null)
      } catch (error: Exception) { promise.reject("DJVU_VIEW_WRITE", error) }
    }
  }

  @ReactMethod
  fun name(path: String, promise: Promise) {
    executor.execute {
      try { checkedLength(path); promise.resolve(documentName(path)) }
      catch (error: Exception) { promise.reject("DJVU_NAME", error) }
    }
  }

  @ReactMethod
  fun listFolder(path: String, promise: Promise) {
    executor.execute {
      try {
        val storage = Environment.getExternalStorageDirectory().canonicalFile
        val roots = libraryFolders.map { File(storage, it).canonicalFile }
        val folders = if (path.isBlank()) {
          roots.filter { it.isDirectory }
        } else {
          val folder = File(path).canonicalFile
          require(roots.any { folder == it || folder.path.startsWith(it.path + File.separator) }) {
            "Folder is outside the Supernote library"
          }
          require(folder.isDirectory && folder.canRead()) { "Folder is not readable" }
          folder.listFiles()?.filter {
            it.isDirectory || (it.isFile && (it.name.endsWith(".djvu", true) || it.name.endsWith(".djv", true)))
          }?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
            ?: throw IllegalStateException("Cannot list folder; check file permissions")
        }
        val result = Arguments.createArray()
        folders.forEach { item ->
          val entry = Arguments.createMap()
          entry.putString("name", item.name)
          entry.putString("path", item.canonicalPath)
          entry.putBoolean("directory", item.isDirectory)
          result.pushMap(entry)
        }
        promise.resolve(result)
      } catch (error: Exception) { promise.reject("DJVU_LIST", error) }
    }
  }

  @ReactMethod
  fun size(path: String, promise: Promise) {
    executor.execute {
      try { promise.resolve(checkedLength(path).toDouble()) }
      catch (error: Exception) { promise.reject("DJVU_FILE", error) }
    }
  }

  @ReactMethod
  fun readChunk(path: String, offset: Double, count: Int, promise: Promise) {
    executor.execute {
      try {
        val fileLength = checkedLength(path)
        val start = offset.toLong()
        require(offset == start.toDouble() && start >= 0 && start < fileLength) { "Invalid offset" }
        require(count in 1..262144) { "Invalid chunk size" }
        val length = minOf(count.toLong(), fileLength - start).toInt()
        val data = ByteArray(length)
        if (path.startsWith("content://")) {
          reactApplicationContext.contentResolver.openFileDescriptor(Uri.parse(path), "r")?.use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).use { stream ->
              stream.channel.position(start)
              var read = 0
              while (read < length) {
                val amount = stream.read(data, read, length - read)
                if (amount < 0) throw java.io.EOFException("File ended unexpectedly")
                read += amount
              }
            }
          } ?: throw IllegalStateException("The selected file is not readable")
        } else {
          RandomAccessFile(checkedFile(path), "r").use { handle ->
            handle.seek(start)
            handle.readFully(data)
          }
        }
        promise.resolve(Base64.encodeToString(data, Base64.NO_WRAP))
      } catch (error: Exception) { promise.reject("DJVU_READ", error) }
    }
  }

  @ReactMethod
  fun readInk(path: String, promise: Promise) {
    executor.execute {
      try {
        val target = inkFile(path)
        promise.resolve(if (target.exists()) target.readText(Charsets.UTF_8) else "{\"version\":1,\"pages\":{}}")
      } catch (error: Exception) { promise.reject("DJVU_INK_READ", error) }
    }
  }

  @ReactMethod
  fun writeInk(path: String, ink: String, promise: Promise) {
    executor.execute {
      try {
        require(ink.toByteArray(Charsets.UTF_8).size <= 8 * 1024 * 1024) { "Ink data is too large" }
        val parsed = JSONObject(ink)
        require(parsed.optInt("version") == 1 && parsed.optJSONObject("pages") != null) { "Invalid ink data" }
        val target = inkFile(path)
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.writeText(ink, Charsets.UTF_8)
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        promise.resolve(null)
      } catch (error: Exception) { promise.reject("DJVU_INK_WRITE", error) }
    }
  }
}
