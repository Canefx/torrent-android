package com.example.btdownloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder

/**
 * 前台服务：保证切到后台、锁屏后下载不被系统杀掉。
 * 引擎由服务持有，界面通过 Binder 访问。
 */
class DownloadService : Service() {

    lateinit var engine: TorrentEngine
        private set

    inner class LocalBinder : Binder() {
        fun service(): DownloadService = this@DownloadService
    }

    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        engine = TorrentEngine(applicationContext)
        engine.start()
        val channel = NotificationChannel(CHANNEL_ID, "下载", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("BT 下载运行中")
            .setContentText("正在后台下载任务")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        engine.stop()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "downloads"
        const val NOTIFICATION_ID = 1
    }
}
