package com.example.btdownloader

import android.content.Context
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.SessionManager
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 下载引擎：封装 libtorrent4j 的 SessionManager。
 * 任务记录保存在 filesDir/torrents（.torrent 副本）和 SharedPreferences（保存路径等）。
 */
class TorrentEngine(private val context: Context) {

    private val session = SessionManager()
    private val torrentDir = File(context.filesDir, "torrents").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("tasks", Context.MODE_PRIVATE)

    // infoHash -> 任务句柄
    private val handles = ConcurrentHashMap<String, TorrentHandle>()
    // infoHash -> 任务名称
    private val names = ConcurrentHashMap<String, String>()
    // 被用户暂停的任务
    private val pausedSet: MutableSet<String> = ConcurrentHashMap.newKeySet()
    // 正在获取元数据的磁力链（key 为磁力链字符串）
    private val pendingMagnets = ConcurrentHashMap<String, PendingMagnet>()

    val downloadDir: File = File(context.getExternalFilesDir(null), "downloads").apply { mkdirs() }

    fun start() {
        session.start()
        restoreSaved()
    }

    fun stop() {
        session.stop()
    }

    /** 添加 .torrent 字节数据。返回 infoHash；重复时抛异常。 */
    fun addTorrentBytes(bytes: ByteArray): String {
        require(bytes.isNotEmpty() && bytes.size <= 10 * 1024 * 1024) { "种子文件为空或超过 10MB" }
        val info = TorrentInfo.bdecode(bytes)
        val hash = info.infoHash().toHex()
        check(!handles.containsKey(hash)) { "该任务已存在" }
        require(info.files().let { fs -> (0 until fs.numFiles()).all { isSafePath(fs.filePath(it)) } }) {
            "种子包含不安全的文件路径，已拒绝"
        }
        File(torrentDir, "$hash.torrent").writeBytes(bytes)
        startTorrent(info, hash)
        saveRecord(hash)
        return hash
    }

    /** 磁力链：阻塞获取元数据（应在后台线程调用），超时 180 秒。 */
    fun addMagnet(magnet: String): String {
        val key = magnet
        val start = System.currentTimeMillis()
        pendingMagnets[key] = PendingMagnet(magnetName(magnet), start, null)
        try {
            val bytes = session.fetchMagnet(magnet, METADATA_TIMEOUT_SEC, torrentDir)
                ?: throw IllegalStateException("获取元数据超时（3 分钟），请检查网络或磁力链")
            return addTorrentBytes(bytes)
        } catch (e: Exception) {
            pendingMagnets[key] = pendingMagnets[key]?.copy(error = e.message ?: "失败")
            throw e
        } finally {
            // 成功后正式任务会出现在 handles 中；失败的记录保留 5 秒供界面显示后移除
            Thread {
                try { Thread.sleep(5_000) } catch (_: InterruptedException) {}
                pendingMagnets.remove(key)
            }.start()
        }
    }

    /** 正在获取元数据的磁力链：名称、开始时间、错误信息。 */
    fun pendingSnapshot(): List<PendingMagnet> = pendingMagnets.values.toList()

    private fun magnetName(magnet: String): String {
        val dn = Regex("[?&]dn=([^&]+)").find(magnet)?.groupValues?.get(1)
        return try { if (dn != null) java.net.URLDecoder.decode(dn, "UTF-8") else "磁力链任务" } catch (_: Exception) { "磁力链任务" }
    }

    fun pause(hash: String) {
        handles[hash]?.pause()
        pausedSet.add(hash)
    }

    fun resume(hash: String) {
        handles[hash]?.resume()
        pausedSet.remove(hash)
    }

    fun remove(hash: String, deleteFiles: Boolean) {
        val h = handles.remove(hash) ?: return
        if (deleteFiles) session.remove(h, SessionHandle.DELETE_FILES) else session.remove(h)
        File(torrentDir, "$hash.torrent").delete()
        names.remove(hash)
        removeRecord(hash)
    }

    /** 当前所有任务的快照，供界面展示。 */
    fun snapshot(): List<TaskItem> = handles.map { (hash, h) ->
        val st = h.status()
        TaskItem(
            infoHash = hash,
            name = names[hash] ?: hash,
            progress = st.progress().toDouble(),
            downloadRate = st.downloadRate().toLong(),
            uploadRate = st.uploadRate().toLong(),
            peers = st.numPeers(),
            paused = pausedSet.contains(hash),
            finished = st.state().name.let { it == "SEEDING" || it == "FINISHED" },
            state = st.state().name,
        )
    }.sortedBy { it.name }

    private fun startTorrent(info: TorrentInfo, hash: String) {
        session.download(info, downloadDir)
        handles[hash] = session.find(info.infoHash())
        names[hash] = info.name()
    }

    private fun restoreSaved() {
        for (hash in prefs.getStringSet("hashes", emptySet()) ?: emptySet()) {
            val f = File(torrentDir, "$hash.torrent")
            if (f.exists()) runCatching { startTorrent(TorrentInfo(f), hash) }
        }
    }

    private fun saveRecord(hash: String) {
        val set = (prefs.getStringSet("hashes", emptySet()) ?: emptySet()).toMutableSet()
        set.add(hash)
        prefs.edit().putStringSet("hashes", set).apply()
    }

    private fun removeRecord(hash: String) {
        val set = (prefs.getStringSet("hashes", emptySet()) ?: emptySet()).toMutableSet()
        set.remove(hash)
        prefs.edit().putStringSet("hashes", set).apply()
    }

    private fun isSafePath(p: String): Boolean =
        !p.startsWith("/") && !p.contains(":") && p.split('/', '\\').none { it == ".." }
}

const val METADATA_TIMEOUT_SEC = 180

data class PendingMagnet(
    val name: String,
    val startedAt: Long,
    val error: String?,
)

data class TaskItem(
    val infoHash: String,
    val name: String,
    val progress: Double,
    val downloadRate: Long,
    val uploadRate: Long,
    val peers: Int,
    val paused: Boolean,
    val finished: Boolean,
    val state: String,
)
