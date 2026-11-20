package com.weelr.larpful2

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File

/** Tiny read-only provider so exported files can be shared with other apps (Drive, mail, …). */
class ShareProvider : ContentProvider() {
    companion object {
        val AUTH: String get() = act.packageName + ".share"
        fun dir(c: Context): File = File(c.cacheDir, "share").apply { mkdirs() }
        fun uriFor(f: File): Uri = Uri.Builder().scheme("content").authority(AUTH).appendPath(f.name).build()
    }

    private fun file(uri: Uri): File? {
        val name = uri.lastPathSegment ?: return null
        if (name.contains('/') || name.contains("..")) return null
        val f = File(dir(context!!), name)
        return if (f.exists()) f else null
    }

    override fun onCreate() = true
    override fun getType(uri: Uri): String {
        val ext = (uri.lastPathSegment ?: "").substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    override fun query(uri: Uri, proj: Array<out String>?, sel: String?, args: Array<out String>?, sort: String?): Cursor? {
        val f = file(uri) ?: return null
        val cols = proj ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val c = MatrixCursor(cols)
        c.addRow(cols.map { if (it == OpenableColumns.SIZE) f.length() else if (it == OpenableColumns.DISPLAY_NAME) f.name else null })
        return c
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val f = file(uri) ?: return null
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
