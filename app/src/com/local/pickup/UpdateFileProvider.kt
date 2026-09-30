package com.local.pickup

import android.content.*
import android.database.*
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.*

class UpdateFileProvider : ContentProvider() {
    override fun onCreate() = true

    private fun checked(uri: Uri): File {
        val c = context ?: throw FileNotFoundException("更新文件不可用")
        if (
            uri.toString() != "content://${c.packageName}.updates/apk" ||
                !Store.prefs(c).getBoolean("update_ready", false)
        )
            throw FileNotFoundException("更新文件不可用")
        try {
            return UpdateFiles.file(c, UpdateFiles.pending(c)).also {
                if (!it.isFile) throw IOException()
            }
        } catch (e: IOException) {
            throw FileNotFoundException("更新文件不可用")
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("只允许读取")
        return ParcelFileDescriptor.open(checked(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = "application/vnd.android.package-archive"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        args: Array<out String>?,
        order: String?,
    ): Cursor? =
        try {
            val file = checked(uri)
            val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
            MatrixCursor(columns).apply {
                addRow(
                    columns
                        .map<String, Any?> {
                            when (it) {
                                OpenableColumns.DISPLAY_NAME -> file.name
                                OpenableColumns.SIZE -> file.length()
                                else -> null
                            }
                        }
                        .toTypedArray()
                )
            }
        } catch (e: FileNotFoundException) {
            null
        }

    override fun insert(uri: Uri, values: ContentValues?): Uri =
        throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int =
        throw UnsupportedOperationException()

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        args: Array<out String>?,
    ): Int = throw UnsupportedOperationException()
}
