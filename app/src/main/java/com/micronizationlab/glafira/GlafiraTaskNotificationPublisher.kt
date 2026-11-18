package com.micronizationlab.glafira

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/** Owner-visible completion notifications for tasks returned by a paired Desktop node. */
internal class GlafiraTaskNotificationPublisher(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(NotificationManager::class.java)

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "GLAFIRA — выполнение задач", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Уведомления о завершении задач на компьютере"
                }
            )
        }
    }

    fun publish(result: GlafiraDistributedTaskResult) {
        val title = if (result.succeeded) "Задача Глафиры завершена" else "Задача Глафиры не выполнена"
        val detail = if (result.succeeded) "Результат готов. Откройте проект в GLAFIRA." else "Не удалось выполнить задачу. Откройте проект для подробностей."
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(appContext, CHANNEL_ID) else Notification.Builder(appContext)
        val notification = builder.setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title).setContentText(detail)
            .setStyle(Notification.BigTextStyle().bigText(detail)).setAutoCancel(true).build()
        runCatching { manager.notify(result.taskId.hashCode(), notification) }
    }

    companion object { private const val CHANNEL_ID = "glafira_desktop_task_results" }
}