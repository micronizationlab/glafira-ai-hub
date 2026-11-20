package com.micronizationlab.glafira

import androidx.work.WorkInfo

/** User-facing status for the manual backup WorkManager chain. */
internal object BackupWorkStatus {
    fun message(state: WorkInfo.State, attempts: Int): String = when (state) {
        WorkInfo.State.ENQUEUED -> if (attempts > 0) {
            "Резервная копия ожидает повторной попытки ($attempts)."
        } else {
            "Резервная копия поставлена в очередь."
        }
        WorkInfo.State.RUNNING -> "Создаю, загружаю и проверяю зашифрованную копию…"
        WorkInfo.State.SUCCEEDED -> "Резервная копия успешно загружена и проверена."
        WorkInfo.State.FAILED -> "Резервное копирование завершилось ошибкой. Подробности указаны ниже."
        WorkInfo.State.BLOCKED -> "Резервное копирование ожидает завершения зависимой операции."
        WorkInfo.State.CANCELLED -> "Резервное копирование отменено."
    }
}
