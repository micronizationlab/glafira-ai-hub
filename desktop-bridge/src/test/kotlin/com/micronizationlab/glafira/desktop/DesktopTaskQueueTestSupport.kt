package com.micronizationlab.glafira.desktop

internal fun desktopTaskQueueTestProtector(marker: Byte = 7): DesktopTaskQueueProtector =
    PairingDesktopTaskQueueProtector.fromPairing(
        marker.toUByte().toString(16).padStart(2, '0').repeat(32),
        ByteArray(32) { marker }
    )
