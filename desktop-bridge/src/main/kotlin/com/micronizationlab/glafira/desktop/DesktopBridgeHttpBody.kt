package com.micronizationlab.glafira.desktop

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** Reads a network response without ever buffering more than limit bytes. */
internal object DesktopBridgeHttpBody {
    fun readBounded(input: InputStream, limit: Int): ByteArray {
        require(limit in 1..(1024 * 1024))
        val output = ByteArrayOutputStream(minOf(limit, 8 * 1024))
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val remainingPlusOne = limit - total + 1
            val count = input.read(buffer, 0, minOf(buffer.size, remainingPlusOne))
            if (count < 0) break
            if (count == 0) {
                val single = input.read()
                if (single < 0) break
                if (total == limit) throw IOException("Bridge response exceeds the size limit.")
                output.write(single)
                total++
                continue
            }
            if (count > limit - total) throw IOException("Bridge response exceeds the size limit.")
            output.write(buffer, 0, count)
            total += count
        }
        return output.toByteArray()
    }
}
