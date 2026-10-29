package com.mydrop.android

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.mydrop.android.engine.SendItem

/** Turns picked or shared content:// items into things the engine can send. */
object SendHelper {
    fun items(resolver: ContentResolver, uris: List<Uri>): List<SendItem> = uris.map { uri ->
        var name = "file"; var size = 0L
        resolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) }
                c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { size = c.getLong(it) }
            }
        }
        SendItem(name, size, resolver.getType(uri) ?: "application/octet-stream") { resolver.openInputStream(uri)!! }
    }
}
