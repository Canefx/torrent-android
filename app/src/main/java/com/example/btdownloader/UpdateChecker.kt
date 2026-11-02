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
 * 在线更新：读取 GitHub Releases 的最新版本，比较 versionCode 后提示下载安装。
 * 仓库需为公开仓库，否则未登录请求会返回 404/401。
 */
class UpdateChecker(private val activity: Activity) {

    fun check(onNewVersion: (UpdateInfo) -> Unit, onUpToDate: () -> Unit, onError: (String) -> Unit) {
        Thread {
            try {
                val rel = JSONObject(httpGet("$API/releases/latest"))
                val tag = rel.optString("tag_name", "").removePrefix("v")
                val changelog = rel.optString("body", "")

                var apkUrl: String? = null
                var manifestUrl: String? = null
                val assets = rel.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        val name = a.optString("name")
                        val url = a.optString("browser_download_url")
                        if (name.endsWith(".apk")) apkUrl = url
                        if (name == "version.json") manifestUrl = url
                    }
                }
                if (apkUrl == null) {
                    activity.runOnUiThread { onUpToDate() }
                    return@Thread
                }

                // version.json 提供精确的 versionCode；缺失时退回按 tag 名比较
                var code = 0
                var name = tag
                if (manifestUrl != null) {
                    val m = JSONObject(httpGet(manifestUrl!!))
                    code = m.optInt("versionCode", 0)
                    name = m.optString("versionName", tag)
                }

                val info = UpdateInfo(code, name.ifEmpty { tag }, changelog, apkUrl!!)
                activity.runOnUiThread {
                    if (info.versionCode > BuildConfig.VERSION_CODE) onNewVersion(info) else onUpToDate()
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
            check(conn.responseCode == 200) { "下载失败 HTTP ${conn.responseCode}" }
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

    /** 打开连接并跟随重定向（GitHub 资产会 302 到 CDN） */
    private fun open(url: String): HttpURLConnection {
        var current = url
        repeat(5) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "BTDownloader")
            }
            val code = conn.responseCode
            if (code in 300..399) {
                current = conn.getHeaderField("Location") ?: return conn
                conn.disconnect()
            } else {
                return conn
            }
        }
        throw IllegalStateException("重定向过多")
    }

    companion object {
        const val REPO = "Canefx/torrent-android"
        const val API = "https://api.github.com/repos/$REPO"
    }
}
