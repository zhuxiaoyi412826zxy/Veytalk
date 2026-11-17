package com.im.nativeapp.core.log

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用内调试日志缓冲。
 *
 * 只在「我的 → 开发调试」开启时采集（关闭后 [i] 直接 return，几乎零开销），
 * 供右上角浮层实时显示，结束时导出到系统 Downloads。
 * 不落盘、不上传，进程内最多保留 [MAX_LINES] 行。
 */
object AppLog {

    private const val MAX_LINES = 500
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    fun start() {
        if (_running.value) return
        _lines.value = emptyList()
        _running.value = true
        i("APP", "开发调试已开启")
    }

    fun stop() {
        if (!_running.value) return
        i("APP", "开发调试已结束")
        _running.value = false
    }

    fun i(tag: String, msg: String) {
        if (!_running.value) return
        val line = "${timeFmt.format(Date())} [$tag] $msg"
        _lines.value = (_lines.value + line).takeLast(MAX_LINES)
    }

    fun w(tag: String, msg: String) = i(tag, "! $msg")

    fun dump(): String = _lines.value.joinToString("\n")

    /** 导出到公共 Downloads（API 29+ 走 MediaStore 无需权限），返回展示用路径 */
    fun export(context: Context): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val fileName = "im-debug-$stamp.txt"
        val content = dump()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("无法创建下载文件")
            context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
                ?: error("无法写入下载文件")
            return "Downloads/$fileName"
        }
        // API 28 及以下没权限写公共目录，落本应用专属 Downloads
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "Downloads")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, fileName)
        file.writeText(content)
        return file.absolutePath
    }
}
