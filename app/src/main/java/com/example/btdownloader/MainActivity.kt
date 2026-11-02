package com.example.btdownloader

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private var engine: TorrentEngine? = null
    private val worker = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var listBox: LinearLayout
    private lateinit var magnetInput: EditText

    private val refresher = object : Runnable {
        override fun run() {
            engine?.let { renderList(it.snapshot()) }
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
        // 启动前台服务，然后绑定以获取引擎
        val svc = Intent(this, DownloadService::class.java)
        startForegroundService(svc)
        bindService(svc, connection, Context.BIND_AUTO_CREATE)
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

    // 从 magnet 链接打开时自动添加
    private fun handleIntent(intent: Intent?) {
        val data = intent?.dataString ?: return
        if (data.startsWith("magnet:")) addMagnet(data)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        magnetInput = EditText(this).apply {
            hint = "粘贴磁力链接 magnet:?xt=urn:btih:..."
            inputType = InputType.TYPE_CLASS_TEXT
        }
        root.addView(magnetInput)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply {
            text = "添加磁力链"
            setOnClickListener {
                val m = magnetInput.text.toString().trim()
                if (m.isEmpty()) toast("请输入磁力链接") else { addMagnet(m); magnetInput.setText("") }
            }
        })
        row.addView(Button(this).apply {
            text = "添加种子文件"
            setOnClickListener { pickTorrent.launch(arrayOf("*/*")) }
        })
        root.addView(row)

        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            addView(listBox, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
    }

    // 系统文件选择器：选择 .torrent 文件
    private val pickTorrent = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val e = engine ?: return@registerForActivityResult toast("引擎未就绪")
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
        toast("正在获取元数据，最长 3 分钟")
        worker.execute {
            try {
                e.addMagnet(magnet)
                ui.post { toast("已添加任务") }
            } catch (ex: Exception) {
                ui.post { toast(ex.message ?: "添加失败") }
            }
        }
    }

    private fun renderList(items: List<TaskItem>) {
        listBox.removeAllViews()
        if (items.isEmpty()) {
            listBox.addView(TextView(this).apply { text = "暂无任务"; gravity = Gravity.CENTER; setPadding(0, 48, 0, 48) })
            return
        }
        for (t in items) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 24, 0, 24)
            }
            card.addView(TextView(this).apply { text = t.name; textSize = 16f })
            val status = when {
                t.paused -> "已暂停"
                t.finished -> "已完成（做种中）"
                else -> "下载中"
            }
            card.addView(TextView(this).apply {
                text = "$status · ${"%.1f".format(t.progress * 100)}% · 节点 ${t.peers}" +
                    " · ↓ ${fmtRate(t.downloadRate)} ↑ ${fmtRate(t.uploadRate)}"
                textSize = 12f
            })
            card.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000
                progress = (t.progress * 1000).toInt()
            })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(Button(this).apply {
                text = if (t.paused) "继续" else "暂停"
                setOnClickListener { if (t.paused) engine?.resume(t.infoHash) else engine?.pause(t.infoHash) }
            })
            row.addView(Button(this).apply {
                text = "删除"
                setOnClickListener { confirmRemove(t) }
            })
            card.addView(row)
            listBox.addView(card)
        }
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

    private fun fmtRate(bytesPerSec: Long): String {
        val kb = bytesPerSec / 1024.0
        return if (kb < 1024) "%.0f KB/s".format(kb) else "%.1f MB/s".format(kb / 1024)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
