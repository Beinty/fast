package com.huc.fasttype

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/**
 * Hands one picture to the app he is writing in.
 *
 * A keyboard cannot pass a gallery URI straight to a chat app — the permission on it
 * belongs to us, not to them. So the picture is copied into our own cache and served
 * from here, and the commit grants read access to that copy alone. Nothing else of
 * ours is reachable through this provider: only the one folder, only reading.
 */
class ImgProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.huc.fasttype.img"
        private const val DIR = "share"

        fun dir(ctx: Context): File {
            val d = File(ctx.cacheDir, DIR)
            if (!d.exists()) d.mkdirs()
            return d
        }

        fun uriFor(name: String): Uri =
            Uri.parse("content://$AUTHORITY/$name")

        /** Keeps the folder from growing: only the newest few copies survive. */
        fun sweep(ctx: Context, keep: Int) {
            try {
                val files = dir(ctx).listFiles() ?: return
                if (files.size <= keep) return
                files.sortByDescending { it.lastModified() }
                for (i in keep until files.size) files[i].delete()
            } catch (_: Throwable) {
            }
        }
    }

    override fun onCreate(): Boolean = true

    private fun fileOf(uri: Uri): File? {
        val name = uri.lastPathSegment ?: return null
        // a name with a path separator in it would reach outside the folder
        if (name.contains('/') || name.contains("..")) return null
        val c = context ?: return null
        val f = File(dir(c), name)
        return if (f.exists()) f else null
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val f = fileOf(uri) ?: return null
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String {
        val n = uri.lastPathSegment ?: return "image/*"
        return when {
            n.endsWith(".png", true) -> "image/png"
            n.endsWith(".webp", true) -> "image/webp"
            n.endsWith(".gif", true) -> "image/gif"
            else -> "image/jpeg"
        }
    }

    /** Some apps ask for the name and size before they will take the picture. */
    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor? {
        val f = fileOf(uri) ?: return null
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val row = arrayOfNulls<Any>(cols.size)
        for (i in cols.indices) {
            row[i] = when (cols[i]) {
                OpenableColumns.DISPLAY_NAME -> f.name
                OpenableColumns.SIZE -> f.length()
                else -> null
            }
        }
        val c = MatrixCursor(cols, 1)
        c.addRow(row)
        return c
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = 0

    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?
    ): Int = 0
}
