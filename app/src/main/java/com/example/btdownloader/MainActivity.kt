package com.example.btdownloader

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private var engine: TorrentEngine? = null
    private val worker = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var listBox: LinearLayout
    private lateinit var magnetInput: EditText

    private val refresher = object : Runnable {
        override fun run() {
            engine?.let { renderList(it.snapshot(), it.pendingSnapshot()) }
            ui.postDelayed(this, 1500)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            engine = (service as DownloadService.LocalBinder).service().engine
            handleIntent(intent)
            ui.post(refresher)
        }
        override fun onServiceDisconnected(name: ComponentName?) { engine = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()

        val svc = Intent(this, DownloadService::class.java)
        startForegroundService(svc)
        bindService(svc, connection, Context.BIND_AUTO_CREATE)

        // 启动时静默检查一次更新；失败不打扰用户
        checkUpdate(manual = false)
    }

    override fun onDestroy() {
        ui.removeCallbacks(refresher)
        runCatching { unbindService(connection) }
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.dataString ?: return
        if (data.startsWith("magnet:")) addMagnet(data)
    }

    // ---------------- UI 构建 ----------------

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C_BG)
        }
        root.addView(header())

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12.dp(), 12.dp(), 12.dp(), 12.dp())
        }

        content.addView(addPanel())
        content.addView(sectionTitle("下载任务"))

        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(listBox)

        val scroll = ScrollView(this).apply {
            setPadding(0, 0, 0, 0)
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun header(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(C_HEADER)
        setPadding(16.dp(), 18.dp(), 16.dp(), 18.dp())

        addView(TextView(this@MainActivity).apply {
            text = "BT 下载"
            setTextColor(Color.WHITE)
            textSize = 19f
            setTypeface(Typeface.DEFAULT_BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        addView(TextView(this@MainActivity).apply {
            text = "v${BuildConfig.VERSION_NAME}"
            setTextColor(Color.parseColor("#B9C6D6"))
            textSize = 12f
        })
    }

    private fun addPanel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(C_WHITE, 16)
        setPadding(14.dp(), 14.dp(), 14.dp(), 14.dp())

        magnetInput = EditText(this@MainActivity).apply {
            hint = "粘贴磁力链接 magnet:?xt=urn:btih:…"
            textSize = 13f
            setTextColor(C_TEXT)
            setHintTextColor(C_HINT)
            setBackground(rounded(C_INPUT, 10))
            setPadding(12.dp(), 10.dp(), 12.dp(), 10.dp())
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 3
        }
        addView(magnetInput, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val row = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(actionButton("添加磁力链", C_PRIMARY, 1f) {
            val m = magnetInput.text.toString().trim()
            if (m.isEmpty()) toast("请输入磁力链接") else { addMagnet(m); magnetInput.setText("") }
        })
        row.addView(space(8))
        row.addView(actionButton("选择种子", C_SECONDARY, 1f) {
            pickTorrentFile()
        })
        row.addView(space(8))
        row.addView(actionButton("检查更新", C_GHOST, 1f, textColor = C_TEXT) {
            checkUpdate(manual = true)
        })
        addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 10.dp()
        })
    }

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(C_MUTED)
        setTypeface(Typeface.DEFAULT_BOLD)
        setPadding(6.dp(), 16.dp(), 6.dp(), 8.dp())
    }

    // ---------------- 列表渲染 ----------------

    private fun renderList(items: List<TaskItem>, pending: List<PendingMagnet>) {
        listBox.removeAllViews()

        if (items.isEmpty() && pending.isEmpty()) {
            listBox.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = rounded(C_WHITE, 16)
                setPadding(16.dp(), 40.dp(), 16.dp(), 40.dp())
                addView(TextView(this@MainActivity).apply {
                    text = "暂无下载任务"
                    textSize = 15f
                    setTextColor(C_MUTED)
                    gravity = Gravity.CENTER
                })
                addView(TextView(this@MainActivity).apply {
                    text = "添加磁力链接或选择 .torrent 文件开始下载"
                    textSize = 12f
                    setTextColor(C_HINT)
                    gravity = Gravity.CENTER
                    setPadding(0, 8.dp(), 0, 0)
                })
            })
            return
        }

        // 正在获取元数据的磁力链
        for (p in pending) {
            listBox.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(C_WHITE, 16)
                setPadding(14.dp(), 12.dp(), 14.dp(), 12.dp())
                setMargins(0, 0, 0, 10.dp())

                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(TextView(this@MainActivity).apply {
                        text = p.name
                        textSize = 15f
                        setTextColor(C_TEXT)
                        setTypeface(Typeface.DEFAULT_BOLD)
                        maxLines = 2
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(pill(if (p.error != null) "失败" else "获取元数据",
                        if (p.error != null) C_DANGER else C_PURPLE))
                })

                val elapsed = ((System.currentTimeMillis() - p.startedAt) / 1000).toInt()
                val left = (METADATA_TIMEOUT_SEC - elapsed).coerceAtLeast(0)
                addView(TextView(this@MainActivity).apply {
                    text = if (p.error != null) "获取失败：${p.error}"
                        else "正在通过 DHT / 节点查找元数据 · 已等待 ${elapsed}s · 剩余 ${left}s"
                    textSize = 11.5f
                    setTextColor(if (p.error != null) C_DANGER else C_MUTED)
                    setPadding(0, 8.dp(), 0, 6.dp())
                })

                addView(ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                    isIndeterminate = p.error == null
                    max = 1000
                    progress = if (p.error != null) 0 else (elapsed * 1000 / METADATA_TIMEOUT_SEC).coerceIn(0, 1000)
                    progressTintList = ColorStateList.valueOf(if (p.error != null) C_DANGER else C_PURPLE)
                    progressBackgroundTintList = ColorStateList.valueOf(C_TRACK)
                    minimumHeight = 6.dp()
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 6.dp()))
            })
        }

        for (t in items) listBox.addView(taskCard(t))
    }

    private fun taskCard(t: TaskItem): LinearLayout {
        val (statusText, statusColor) = when {
            t.paused -> "已暂停" to C_MUTED
            t.finished -> "做种中" to C_SUCCESS
            t.progress > 0 -> "下载中" to C_PRIMARY
            else -> "连接中" to C_PURPLE
        }
        val pct = (t.progress * 100)

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(C_WHITE, 16)
            setPadding(14.dp(), 12.dp(), 14.dp(), 12.dp())
            setMargins(0, 0, 0, 10.dp())

            // 标题行：名称 + 状态
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = t.name
                    textSize = 15f
                    setTextColor(C_TEXT)
                    setTypeface(Typeface.DEFAULT_BOLD)
                    maxLines = 2
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(pill(statusText, statusColor))
            })

            // 进度条
            addView(ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000
                progress = (t.progress * 1000).toInt()
                progressTintList = ColorStateList.valueOf(if (t.finished) C_SUCCESS else C_PRIMARY)
                progressBackgroundTintList = ColorStateList.valueOf(C_TRACK)
                minimumHeight = 6.dp()
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 6.dp()).apply {
                topMargin = 10.dp()
            })

            // 百分比与节点数
            addView(TextView(this@MainActivity).apply {
                text = "${"%.1f".format(pct)}%  ·  节点 ${t.peers}  ·  ${t.state}"
                textSize = 11.5f
                setTextColor(C_MUTED)
                setPadding(0, 8.dp(), 0, 2.dp())
            })

            // 速度
            addView(TextView(this@MainActivity).apply {
                text = "↓ ${fmtRate(t.downloadRate)}    ↑ ${fmtRate(t.uploadRate)}"
                textSize = 12f
                setTextColor(C_TEXT)
                setTypeface(Typeface.MONOSPACE)
            })

            // 操作
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 10.dp(), 0, 0)
                addView(actionButton(if (t.paused) "继续" else "暂停", C_PRIMARY, 1f, small = true) {
                    if (t.paused) engine?.resume(t.infoHash) else engine?.pause(t.infoHash)
                })
                addView(space(8))
                addView(actionButton("删除", C_DANGER, 1f, small = true) { confirmRemove(t) })
            })
        }
    }

    private fun pill(text: String, color: Int): TextView = TextView(this).apply {
        this.text = text
        textSize = 10.5f
        setTextColor(Color.WHITE)
        background = rounded(color, 20)
        setPadding(9.dp(), 3.dp(), 9.dp(), 3.dp())
    }

    private fun actionButton(
        label: String,
        bg: Int,
        weight: Float,
        small: Boolean = false,
        textColor: Int = Color.WHITE,
        onClick: () -> Unit,
    ): TextView = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(textColor)
        textSize = if (small) 12.5f else 13f
        background = rounded(bg, 10)
        setPadding(6.dp(), if (small) 8.dp() else 11.dp(), 6.dp(), if (small) 8.dp() else 11.dp())
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
    }

    private fun space(dp: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp.dp(), 1)
    }

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusDp.dp().toFloat()
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    private fun LinearLayout.setMargins(l: Int, t: Int, r: Int, b: Int) {
        layoutParams = (layoutParams as? ViewGroup.MarginLayoutParams)?.apply {
            setMargins(l, t, r, b)
        } ?: LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(l, t, r, b) }
    }

    // ---------------- 交互 ----------------

    /** 打开系统文件选择器挑选 .torrent */
    private fun pickTorrentFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQ_TORRENT)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_TORRENT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val e = engine ?: return toast("引擎未就绪")
        worker.execute {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("无法读取文件")
                e.addTorrentBytes(bytes)
                ui.post { toast("已添加种子") }
            } catch (ex: Exception) {
                ui.post { toast(ex.message ?: "添加失败") }
            }
        }
    }

    private fun addMagnet(magnet: String) {
        val e = engine ?: return toast("引擎未就绪")
        toast("正在获取元数据")
        worker.execute {
            try {
                e.addMagnet(magnet)
                ui.post { toast("已添加任务") }
            } catch (ex: Exception) {
                ui.post { toast(ex.message ?: "添加失败") }
            }
        }
    }

    private fun checkUpdate(manual: Boolean) {
        if (manual) toast("正在检查更新…")
        UpdateChecker(this).check(
            onNewVersion = { info ->
                AlertDialog.Builder(this)
                    .setTitle("发现新版本 ${info.versionName}")
                    .setMessage(info.changelog.ifBlank { "是否下载并安装？" })
                    .setPositiveButton("下载更新") { _, _ ->
                        UpdateChecker(this).downloadAndInstall(info) { msg -> toast(msg) }
                    }
                    .setNegativeButton("稍后", null)
                    .show()
            },
            onUpToDate = { if (manual) toast("已是最新版本") },
            onError = { msg -> if (manual) toast("检查更新失败：$msg") },
        )
    }

    private fun confirmRemove(t: TaskItem) {
        AlertDialog.Builder(this)
            .setTitle("删除任务")
            .setMessage(t.name)
            .setPositiveButton("仅移除任务") { _, _ -> engine?.remove(t.infoHash, false) }
            .setNeutralButton("移除并删除文件") { _, _ -> engine?.remove(t.infoHash, true) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun fmtSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = bytes.toDouble()
        var i = 0
        while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
        return if (i == 0) "${v.toInt()} ${units[i]}" else "%.1f %s".format(v, units[i])
    }

    private fun fmtRate(bytesPerSec: Long): String {
        val kb = bytesPerSec / 1024.0
        return if (kb < 1024) "%.0f KB/s".format(kb) else "%.1f MB/s".format(kb / 1024)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQ_TORRENT = 1001
        private val C_BG = Color.parseColor("#F2F4F7")
        private val C_HEADER = Color.parseColor("#243447")
        private val C_WHITE = Color.WHITE
        private val C_TEXT = Color.parseColor("#1F2933")
        private val C_MUTED = Color.parseColor("#6B7785")
        private val C_HINT = Color.parseColor("#9AA5B1")
        private val C_INPUT = Color.parseColor("#F2F4F7")
        private val C_TRACK = Color.parseColor("#E4E9F0")
        private val C_PRIMARY = Color.parseColor("#3B7DD8")
        private val C_SECONDARY = Color.parseColor("#5B6B80")
        private val C_GHOST = Color.parseColor("#E4E9F0")
        private val C_SUCCESS = Color.parseColor("#2E9D5B")
        private val C_DANGER = Color.parseColor("#D9534F")
        private val C_PURPLE = Color.parseColor("#8E6CD8")
    }
}
