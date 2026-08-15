package com.cfks.supersys.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

object UriUtils {

    private const val TAG = "UriUtils"

    data class ReadResult(val content: String?, val error: String?)

    data class WriteResult(val success: Boolean, val error: String?)

    fun readUri(context: Context, url: String): String? {
        return readUriDetailed(context, url).content
    }

    fun readUriDetailed(context: Context, url: String): ReadResult {
        return try {
            val uri = Uri.parse(url)
            val resolver = context.contentResolver
            resolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            val inputStream = resolver.openInputStream(uri)
                ?: return ReadResult(null, "openInputStream returned null")
            val reader = BufferedReader(InputStreamReader(inputStream))
            val content = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                content.append(line).append("\n")
            }
            reader.close()
            ReadResult(content.toString(), null)
        } catch (e: Exception) {
            Log.e(TAG, "readUri error: ${e.message}")
            ReadResult(null, e.toString())
        }
    }

    fun writeUri(context: Context, url: String, content: String): Boolean {
        return writeUriDetailed(context, url, content).success
    }

    fun writeUriDetailed(context: Context, url: String, content: String): WriteResult {
        return try {
            val uri = Uri.parse(url)
            val resolver = context.contentResolver
            resolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            val os = resolver.openOutputStream(uri)
                ?: return WriteResult(false, "openOutputStream returned null")
            os.write(content.toByteArray())
            os.flush()
            os.close()
            WriteResult(true, null)
        } catch (e: Exception) {
            Log.e(TAG, "writeUri error: ${e.message}")
            WriteResult(false, e.toString())
        }
    }
}
