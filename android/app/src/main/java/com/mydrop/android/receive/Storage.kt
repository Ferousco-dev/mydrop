package com.mydrop.android.receive

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream

class SavedFile(val name: String, val uri: Uri, val mime: String)

/** Saves received files into the phone's public Downloads/MyDrop folder, so every app and the Files app can see them. */
object Storage {
    fun create(context: Context, name: String, mime: String): Pair<OutputStream, SavedFile> {
        val safeMime = mime.ifEmpty { "application/octet-stream" }
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, safeMime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/MyDrop")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw java.io.IOException("Couldn't create $name in Downloads")
            val raw = resolver.openOutputStream(uri) ?: throw java.io.IOException("Couldn't open $name")
            val stream = object : FilterOutputStream(raw.buffered(1024 * 1024)) {
                override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len) }
                override fun close() {
                    super.close()
                    resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                }
            }
            // The name the system actually used may differ if there was a clash.
            val finalName = resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: name
            return stream to SavedFile(finalName, uri, safeMime)
        }
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "MyDrop").apply { mkdirs() }
        var target = File(dir, name)
        var n = 1
        while (target.exists()) { target = File(dir, "${target.nameWithoutExtension} ($n).${target.extension}"); n++ }
        return target.outputStream().buffered() to SavedFile(target.name, Uri.fromFile(target), safeMime)
    }
}
