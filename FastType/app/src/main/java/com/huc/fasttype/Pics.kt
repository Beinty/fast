package com.huc.fasttype

import android.Manifest
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import java.io.File
import java.io.FileOutputStream

/**
 * The last picture he made, ready to send.
 *
 * A screenshot is not put on the clipboard by the system — it is written to the
 * gallery. So rather than asking him to copy it first, the keyboard looks at what
 * the gallery gained most recently and holds it out. That costs one permission,
 * asked once; after that nothing is ever shown or asked again.
 */
object Pics {

    class Shot(val id: Long, val uri: Uri, val at: Long, val shot: Boolean)

    /** How fresh a picture must be before the key offers it on its own. */
    private const val FRESH_MS = 15 * 60 * 1000L

    @Volatile var ready: Uri? = null
        private set

    @Volatile var readyThumb: Bitmap? = null
        private set

    /** Ids already sent or dismissed, so the same shot is not offered twice. */
    private var usedId = -1L
    private var lastSeenId = -1L

    fun permission(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE

    fun allowed(ctx: Context): Boolean = try {
        ctx.checkSelfPermission(permission()) == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    // ---- what the gallery has ----------------------------------------------

    fun recent(ctx: Context, n: Int): List<Shot> {
        if (!allowed(ctx)) return emptyList()
        val out = ArrayList<Shot>(n)
        try {
            val cols = arrayListOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATE_ADDED
            )
            if (Build.VERSION.SDK_INT >= 29) cols.add(MediaStore.Images.Media.RELATIVE_PATH)
            else @Suppress("DEPRECATION") cols.add(MediaStore.Images.Media.DATA)

            ctx.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                cols.toTypedArray(), null, null,
                MediaStore.Images.Media.DATE_ADDED + " DESC"
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val iAt = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val iPath = c.getColumnIndex(cols[2])
                while (c.moveToNext() && out.size < n) {
                    val id = c.getLong(iId)
                    // DATE_ADDED is in seconds
                    val at = c.getLong(iAt) * 1000L
                    val path = if (iPath >= 0) (c.getString(iPath) ?: "") else ""
                    val shot = path.contains("screenshot", true) ||
                        path.contains("لقطات", true)
                    out.add(
                        Shot(
                            id,
                            Uri.withAppendedPath(
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString()
                            ),
                            at, shot
                        )
                    )
                }
            }
        } catch (_: Throwable) {
        }
        return out
    }

    /**
     * Looks for something new worth offering: a picture made in the last few minutes
     * that he has not already sent. Called when the keyboard appears and whenever the
     * gallery changes while it is open.
     */
    fun refresh(ctx: Context, thumbPx: Int): Boolean {
        if (!Store.kbPics) {
            if (ready != null) { clear(); return true }
            return false
        }
        val top = recent(ctx, 1).firstOrNull()
        if (top == null) return false
        if (top.id == usedId || top.id == lastSeenId) return false
        if (System.currentTimeMillis() - top.at > FRESH_MS) {
            lastSeenId = top.id
            return false
        }
        lastSeenId = top.id
        val bm = thumb(ctx, top.uri, thumbPx) ?: return false
        readyThumb?.recycle()
        readyThumb = bm
        ready = top.uri
        return true
    }

    /** Something was copied: if it is a picture, hold it out the same way. */
    fun fromClipboard(ctx: Context, thumbPx: Int): Boolean {
        if (!Store.kbPics) return false
        try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return false
            val clip = cm.primaryClip ?: return false
            val d = clip.description ?: return false
            var isImage = false
            for (i in 0 until d.mimeTypeCount) {
                if (d.getMimeType(i).startsWith("image/")) { isImage = true; break }
            }
            if (!isImage) return false
            val uri = clip.getItemAt(0)?.uri ?: return false
            val bm = thumb(ctx, uri, thumbPx) ?: return false
            readyThumb?.recycle()
            readyThumb = bm
            ready = uri
            usedId = -1L
            return true
        } catch (_: Throwable) {
            return false
        }
    }

    fun clear() {
        ready = null
        readyThumb?.recycle()
        readyThumb = null
    }

    // ---- turning one into something an app will accept ----------------------

    fun thumb(ctx: Context, uri: Uri, px: Int): Bitmap? {
        return try {
            val opts = BitmapFactory.Options()
            opts.inJustDecodeBounds = true
            ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
            var sample = 1
            val longest = maxOf(opts.outWidth, opts.outHeight)
            while (longest / sample > px * 2 && sample < 64) sample *= 2
            val o2 = BitmapFactory.Options()
            o2.inSampleSize = sample
            ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, o2)
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** Copies the picture into our own cache and returns a link the app may read. */
    private fun stage(ctx: Context, uri: Uri): Pair<Uri, String>? {
        return try {
            val type = ctx.contentResolver.getType(uri) ?: "image/jpeg"
            val ext = when {
                type.contains("png") -> ".png"
                type.contains("webp") -> ".webp"
                type.contains("gif") -> ".gif"
                else -> ".jpg"
            }
            val name = "p" + System.currentTimeMillis() + ext
            val out = File(ImgProvider.dir(ctx), name)
            ctx.contentResolver.openInputStream(uri)?.use { ins ->
                FileOutputStream(out).use { o -> ins.copyTo(o, 64 * 1024) }
            } ?: return null
            if (out.length() == 0L) { out.delete(); return null }
            ImgProvider.sweep(ctx, 6)
            Pair(ImgProvider.uriFor(name), type)
        } catch (_: Throwable) {
            null
        }
    }

    /** True when this field says it will take a picture from a keyboard. */
    fun accepts(info: EditorInfo?): Boolean {
        val types = info?.contentMimeTypes ?: return false
        for (t in types) {
            if (t.startsWith("image/") || t == "*/*") return true
        }
        return false
    }

    /**
     * Sends [uri] into whatever he is writing in.
     *
     * Returns false when the app refuses it — a keyboard cannot force a picture into
     * a field, and saying so plainly is better than appearing to do nothing.
     */
    fun send(ctx: Context, ic: InputConnection?, info: EditorInfo?, uri: Uri): Boolean {
        if (ic == null || !accepts(info)) return false
        val staged = stage(ctx, uri) ?: return false
        return try {
            val content = InputContentInfo(
                staged.first,
                ClipDescription("صورة", arrayOf(staged.second))
            )
            val ok = ic.commitContent(
                content, InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null
            )
            if (ok) {
                val top = ready
                if (top != null) {
                    usedId = top.lastPathSegment?.toLongOrNull() ?: -1L
                }
                clear()
            }
            ok
        } catch (_: Throwable) {
            false
        }
    }
}
