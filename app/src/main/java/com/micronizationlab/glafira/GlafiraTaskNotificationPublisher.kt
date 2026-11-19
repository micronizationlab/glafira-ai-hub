package com.micronizationlab.glafira

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/** Owner-visible completion notifications for tasks returned by a paired Desktop node. */
internal class GlafiraTaskNotificationPublisher(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(NotificationManager::class.java)

    init {
        // minSdk is 26, so notification channels are always available.
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "GLAFIRA — выполнение задач", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Уведомления о завершении задач на компьютере"
            }
        )
    }

    fun publish(result: GlafiraDistributedTaskResult) {
        val title = if (result.succeeded) "Задача Глафиры завершена" else "Задача Глафиры не выполнена"
        val detail = if (result.succeeded) "Результат готов. Откройте проект в GLAFIRA." else "Не удалось выполнить задачу. Откройте проект для подробностей."
        val builder = Notification.Builder(appContext, CHANNEL_ID)
        val notification = builder.setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title).setContentText(detail)
            .setStyle(Notification.BigTextStyle().bigText(detail)).setAutoCancel(true).build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    companion object {
        private const val CHANNEL_ID = "glafira_desktop_task_results"
        private const val NOTIFICATION_ID = 47_324
    }
}