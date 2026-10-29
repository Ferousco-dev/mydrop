package com.mydrop.android.share

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Packs a folder chosen with the system picker into one zip file, ready to send. */
object FolderZip {
    fun zip(context: Context, tree: Uri): File {
        val root = DocumentFile.fromTreeUri(context, tree) ?: throw java.io.IOException("Can't open that folder")
        val out = File(context.cacheDir, "${root.name ?: "folder"}.zip")
        ZipOutputStream(out.outputStream().buffered(256 * 1024)).use { zip ->
            fun add(dir: DocumentFile, prefix: String) {
                for (f in dir.listFiles()) {
                    val name = prefix + (f.name ?: continue)
                    if (f.isDirectory) { zip.putNextEntry(ZipEntry("$name/")); zip.closeEntry(); add(f, "$name/") }
                    else {
                        zip.putNextEntry(ZipEntry(name))
                        context.contentResolver.openInputStream(f.uri)?.use { it.copyTo(zip, 256 * 1024) }
                        zip.closeEntry()
                    }
                }
            }
            add(root, "${root.name ?: "folder"}/")
        }
        return out
    }
}
