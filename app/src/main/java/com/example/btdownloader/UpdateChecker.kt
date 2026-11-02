package com.example.btdownloader

import android.app.Activity
import android.app.ProgressDialog
import android.content.Intent
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** 线上新版本信息 */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val changelog: String,
    val apkUrl: String,
)

/**
 * 在线更新：从 GitHub Releases 的 latest 读取 version.json，比较 versionCode。
 *
 * 刻意不使用 api.github.com：匿名 API 有每小时 60 次的配额，
 * 手机共用出口 IP 时极易被耗尽（返回 403）。这里改用
 * /releases/latest/download/<文件名> 这种由 GitHub 直接重定向到 CDN 的地址，
 * 不受 API 限流影响，也不需要 token。
 */
class UpdateChecker(private val activity: Activity) {

    fun check(onNewVersion: (UpdateInfo) -> Unit, onUpToDate: () -> Unit, onError: (String) -> Unit) {
        Thread {
            try {
                val json = JSONObject(httpGet("$BASE/version.json"))
                val code = json.optInt("versionCode", 0)
                val name = json.optString("versionName", "")
                val changelog = json.optString("changelog", "")
                val apkName = json.optString("apk", "BtDownloader.apk")

                if (code > BuildConfig.VERSION_CODE) {
                    val info = UpdateInfo(code, name.ifEmpty { "新版本" }, changelog, "$BASE/$apkName")
                    activity.runOnUiThread { onNewVersion(info) }
                } else {
                    activity.runOnUiThread { onUpToDate() }
                }
            } catch (e: Exception) {
                activity.runOnUiThread { onError(e.message ?: "检查更新失败") }
            }
        }.start()
    }

    /** 下载 APK 并通过系统安装器打开 */
    fun downloadAndInstall(info: UpdateInfo, onError: (String) -> Unit) {
        val dialog = ProgressDialog(activity).apply {
            setTitle("下载更新 ${info.versionName}")
            setMessage("准备中…")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            setMax(100)
            setCancelable(false)
            show()
        }
        Thread {
            try {
                val dir = File(activity.cacheDir, "updates").apply { mkdirs() }
                val apk = File(dir, "update.apk")
                if (apk.exists()) apk.delete()
                download(info.apkUrl, apk) { pct ->
                    activity.runOnUiThread { dialog.progress = pct }
                }
                activity.runOnUiThread {
                    dialog.dismiss()
                    install(apk, onError)
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    dialog.dismiss()
                    onError("下载失败：${e.message}")
                }
            }
        }.start()
    }

    private fun install(apk: File, onError: (String) -> Unit) {
        try {
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apk)
            activity.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (e: Exception) {
            onError("无法启动安装器：${e.message}")
        }
    }

    // ---------- HTTP ----------

    private fun httpGet(url: String): String {
        val conn = open(url)
        try {
            check(conn.responseCode == 200) { "HTTP ${conn.responseCode}" }
            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    private fun download(url: String, dest: File, onProgress: (Int) -> Unit) {
        val conn = open(url)
        try {
            check(conn.responseCode == 200) { "HTTP ${conn.responseCode}" }
            val total = conn.contentLengthLong
            var done = 0L
            conn.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress(((done * 100) / total).toInt())
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        check(dest.length() > 100_000) { "下载的 APK 过小，可能不是有效文件" }
    }

    /** 打开连接并跟随重定向（GitHub 的 release 下载会 302 到 CDN） */
    private fun open(url: String): HttpURLConnection {
        var current = url
        repeat(6) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "BTDownloader")
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val next = conn.getHeaderField("Location")
                conn.disconnect()
                if (next != null) {
                    current = next
                    return@repeat
                }
            }
            return conn
        }
        throw IllegalStateException("重定向过多")
    }

    companion object {
        const val REPO = "Canefx/torrent-android"
        const val BASE = "https://github.com/$REPO/releases/latest/download"
    }
}
