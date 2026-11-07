package com.ali.downloader.bridge

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.ali.downloader.worker.Publisher
import java.io.File

/** کتابخانه: فهرست فایل‌های دانلودشده در پوشهٔ خروجی، باز کردن، اشتراک‌گذاری و حذف. */
object LibraryOps {

    data class Item(val name: String, val uri: String, val size: Long, val date: Long, val mime: String) {
        fun toMap(): Map<String, Any> = mapOf("name" to name, "uri" to uri, "size" to size, "date" to date, "mime" to mime)
    }

    fun list(ctx: Context, customTree: String): List<Map<String, Any>> {
        val out = ArrayList<Item>()
        try {
            if (Build.VERSION.SDK_INT >= 29) out.addAll(listMediaStore(ctx)) else out.addAll(listLegacy())
        } catch (_: SecurityException) {
        }
        if (customTree.isNotEmpty()) {
            try {
                out.addAll(listTree(ctx, Uri.parse(customTree)))
            } catch (_: Exception) {
            }
        }
        return out.filter { !it.name.endsWith(".part") }
            .distinctBy { it.uri }
            .sortedByDescending { it.date }
            .map { it.toMap() }
    }

    private fun listMediaStore(ctx: Context): List<Item> {
        val res = ArrayList<Item>()
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val proj = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.MIME_TYPE,
        )
        val sel = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.IS_PENDING}=0"
        val args = arrayOf(Environment.DIRECTORY_DOWNLOADS + "/" + Publisher.FOLDER_NAME + "%")
        ctx.contentResolver.query(collection, proj, sel, args, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val name = c.getString(1) ?: continue
                res.add(
                    Item(
                        name = name,
                        uri = ContentUris.withAppendedId(collection, id).toString(),
                        size = c.getLong(2),
                        date = c.getLong(3) * 1000,
                        mime = c.getString(4) ?: Publisher.mimeOf(name),
                    )
                )
            }
        }
        return res
    }

    @Suppress("DEPRECATION")
    private fun legacyDir() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), Publisher.FOLDER_NAME)

    private fun listLegacy(): List<Item> =
        legacyDir().listFiles()?.filter { it.isFile }?.map {
            Item(it.name, Uri.fromFile(it).toString(), it.length(), it.lastModified(), Publisher.mimeOf(it.name))
        } ?: emptyList()

    private fun listTree(ctx: Context, tree: Uri): List<Item> {
        val res = ArrayList<Item>()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val proj = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        ctx.contentResolver.query(children, proj, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val mime = c.getString(4) ?: ""
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) continue
                val name = c.getString(1) ?: continue
                res.add(
                    Item(
                        name = name,
                        uri = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)).toString(),
                        size = c.getLong(2),
                        date = c.getLong(3),
                        mime = mime.ifEmpty { Publisher.mimeOf(name) },
                    )
                )
            }
        }
        return res
    }

    /** آدرس قابل اشتراک: فایل‌های مسیر مستقیم (اندروید ۷ تا ۹) از طریق FileProvider. */
    fun shareableUri(ctx: Context, uriStr: String): Uri {
        val uri = Uri.parse(uriStr)
        return if (uri.scheme == "file") {
            FileProvider.getUriForFile(ctx, ctx.packageName + ".files", File(uri.path!!))
        } else uri
    }

    fun openIntent(ctx: Context, uriStr: String, mime: String): Intent {
        val u = shareableUri(ctx, uriStr)
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(u, mime.ifEmpty { "*/*" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(view, "باز کردن با")
    }

    fun shareIntent(ctx: Context, uriStr: String, mime: String): Intent {
        val u = shareableUri(ctx, uriStr)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime.ifEmpty { "*/*" }
            putExtra(Intent.EXTRA_STREAM, u)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "اشتراک‌گذاری")
    }

    /** حذف مستقیم؛ اگر اندروید تأیید کاربر بخواهد SecurityException پرتاب می‌شود. */
    fun deleteDirect(ctx: Context, uriStr: String): Boolean {
        val uri = Uri.parse(uriStr)
        return when {
            uri.scheme == "file" -> File(uri.path!!).delete()
            DocumentsContract.isDocumentUri(ctx, uri) -> DocumentsContract.deleteDocument(ctx.contentResolver, uri)
            else -> ctx.contentResolver.delete(uri, null, null) > 0
        }
    }

    fun storageInfo(): Map<String, Long> {
        val dir = Environment.getExternalStorageDirectory()
        return try {
            val st = StatFs(dir.absolutePath)
            mapOf("free" to st.availableBytes, "total" to st.totalBytes)
        } catch (_: Exception) {
            mapOf("free" to -1L, "total" to -1L)
        }
    }
}
