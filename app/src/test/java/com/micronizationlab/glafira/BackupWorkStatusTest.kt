package com.micronizationlab.glafira

import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupWorkStatusTest {
    @Test fun reportsQueueAndRetryStates() {
        assertEquals("Резервная копия поставлена в очередь.", BackupWorkStatus.message(WorkInfo.State.ENQUEUED, 0))
        assertEquals("Резервная копия ожидает повторной попытки (2).", BackupWorkStatus.message(WorkInfo.State.ENQUEUED, 2))
    }

    @Test fun reportsRunningAndSuccessfulStates() {
        assertEquals("Создаю, загружаю и проверяю зашифрованную копию…", BackupWorkStatus.message(WorkInfo.State.RUNNING, 0))
        assertEquals("Резервная копия успешно загружена и проверена.", BackupWorkStatus.message(WorkInfo.State.SUCCEEDED, 1))
    }

    @Test fun reportsFailureBlockedAndCancelledStates() {
        assertEquals("Резервное копирование завершилось ошибкой. Подробности указаны ниже.", BackupWorkStatus.message(WorkInfo.State.FAILED, 3))
        assertEquals("Резервное копирование ожидает завершения зависимой операции.", BackupWorkStatus.message(WorkInfo.State.BLOCKED, 0))
        assertEquals("Резервное копирование отменено.", BackupWorkStatus.message(WorkInfo.State.CANCELLED, 0))
    }
}
