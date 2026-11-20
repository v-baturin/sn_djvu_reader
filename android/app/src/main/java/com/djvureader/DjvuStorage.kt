package com.djvureader

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.util.Base64
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import org.json.JSONObject

/** Keeps the same preference keys and ink files as the previous React Native release. */
class DjvuStorage(private val context: Context) {
  data class Entry(val name: String, val path: String, val directory: Boolean)
  private val preferences = context.getSharedPreferences("djvu-reader", Context.MODE_PRIVATE)
  private val roots = listOf("Document", "INBOX", "MyStyle", "EXPORT", "Note", "SCREENSHOT")
  private val maxFileSize = 268435456L

  fun lastFile(): String? = preferences.getString("lastFile", null)

  fun rememberFile(path: String) {
    checkedLength(path)
    preferences.edit().putString("lastFile", path).apply()
  }

  fun name(path: String): String {
    if (!path.startsWith("content://")) return File(path).name
    val uri = Uri.parse(path)
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
      if (it.moveToFirst()) return it.getString(0) ?: ""
    }
    return uri.lastPathSegment ?: ""
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

  fun checkedLength(path: String): Long {
    val displayName = name(path)
    require(displayName.endsWith(".djvu", true) || displayName.endsWith(".djv", true)) {
      "Choose a .djvu or .djv file"
    }
    val length = if (path.startsWith("content://")) {
      context.contentResolver.openFileDescriptor(Uri.parse(path), "r")?.use { it.statSize }
        ?: throw IllegalStateException("The selected file is not readable")
    } else checkedFile(path).length()
    require(length in 1L..maxFileSize) { "File must be between 1 byte and 256 MB" }
    return length
  }

  private fun fileKey(path: String): String {
    checkedLength(path)
    val canonicalPath = if (path.startsWith("content://")) path else File(path).canonicalPath
    return MessageDigest.getInstance("SHA-256").digest(canonicalPath.toByteArray(Charsets.UTF_8))
      .joinToString("") { "%02x".format(it) }
  }

  private fun inkFile(path: String): File {
    val folder = File(context.filesDir, "djvu-reader-ink")
    require(folder.isDirectory || folder.mkdirs()) { "Cannot create ink storage" }
    return File(folder, "${fileKey(path)}.json")
  }

  fun readInk(path: String): String {
    val target = inkFile(path)
    return if (target.exists()) target.readText(Charsets.UTF_8) else "{\"version\":1,\"pages\":{}}"
  }

  fun writeInk(path: String, ink: String) {
    require(ink.toByteArray(Charsets.UTF_8).size <= 8 * 1024 * 1024) { "Ink data is too large" }
    val parsed = JSONObject(ink)
    require(parsed.optInt("version") == 1 && parsed.optJSONObject("pages") != null) { "Invalid ink data" }
    val target = inkFile(path)
    val temp = File(target.parentFile, target.name + ".tmp")
    temp.writeText(ink, Charsets.UTF_8)
    Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
  }

  fun readViewState(path: String): String = preferences.getString("view:${fileKey(path)}", "{}") ?: "{}"

  fun writeViewState(path: String, state: String) {
    require(state.toByteArray(Charsets.UTF_8).size <= 2048) { "View state is too large" }
    require(JSONObject(state).optInt("page", 0) >= 1) { "Invalid page" }
    preferences.edit().putString("view:${fileKey(path)}", state).apply()
  }

  fun listFolder(path: String): List<Entry> {
    val storage = Environment.getExternalStorageDirectory().canonicalFile
    val allowed = roots.map { File(storage, it).canonicalFile }
    val items = if (path.isBlank()) allowed.filter { it.isDirectory } else {
      val folder = File(path).canonicalFile
      require(allowed.any { folder == it || folder.path.startsWith(it.path + File.separator) }) {
        "Folder is outside the Supernote library"
      }
      require(folder.isDirectory && folder.canRead()) { "Folder is not readable" }
      folder.listFiles()?.filter {
        it.isDirectory || (it.isFile && (it.name.endsWith(".djvu", true) || it.name.endsWith(".djv", true)))
      }?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
        ?: throw IllegalStateException("Cannot list folder; check file permissions")
    }
    return items.map { Entry(it.name, it.canonicalPath, it.isDirectory) }
  }

  fun readChunk(path: String, offset: Long, count: Int): String {
    val fileLength = checkedLength(path)
    require(offset >= 0 && offset < fileLength) { "Invalid offset" }
    require(count in 1..262144) { "Invalid chunk size" }
    val length = minOf(count.toLong(), fileLength - offset).toInt()
    val data = ByteArray(length)
    if (path.startsWith("content://")) {
      context.contentResolver.openFileDescriptor(Uri.parse(path), "r")?.use { descriptor ->
        FileInputStream(descriptor.fileDescriptor).use { stream ->
          stream.channel.position(offset)
          var read = 0
          while (read < length) {
            val amount = stream.read(data, read, length - read)
            if (amount < 0) throw EOFException("File ended unexpectedly")
            read += amount
          }
        }
      } ?: throw IllegalStateException("The selected file is not readable")
    } else {
      RandomAccessFile(checkedFile(path), "r").use { handle ->
        handle.seek(offset)
        handle.readFully(data)
      }
    }
    return Base64.encodeToString(data, Base64.NO_WRAP)
  }
}
