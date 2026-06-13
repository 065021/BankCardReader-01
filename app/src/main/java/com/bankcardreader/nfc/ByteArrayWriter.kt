package com.bankcardreader.nfc

/**
 * 简单的字节数组写入器，用于构建 APDU 命令数据。
 */
class ByteArrayWriter {
    private val chunks = mutableListOf<ByteArray>()

    fun write(vararg bytes: Byte) {
        chunks.add(bytes)
    }

    fun writeBytes(bytes: ByteArray) {
        chunks.add(bytes)
    }

    fun toByteArray(): ByteArray {
        val totalSize = chunks.sumOf { it.size }
        val result = ByteArray(totalSize)
        var pos = 0
        for (chunk in chunks) {
            System.arraycopy(chunk, 0, result, pos, chunk.size)
            pos += chunk.size
        }
        return result
    }
}
