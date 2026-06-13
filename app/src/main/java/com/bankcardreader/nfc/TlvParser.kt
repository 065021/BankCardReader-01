package com.bankcardreader.nfc

/**
 * TLV（Tag-Length-Value）数据解析器，用于解析 EMV 卡片的 BER-TLV 编码响应。
 */
object TlvParser {

    /**
     * 解析 TLV 编码的字节数组，返回扁平化的标签->值映射。
     * 对于构造数据对象（constructed），递归解析内部 TLV。
     */
    fun parse(data: ByteArray): Map<ByteArray, ByteArray> {
        val result = mutableMapOf<ByteArray, ByteArray>()
        parseRecursive(data, 0, data.size, result)
        return result
    }

    /**
     * 在 TLV 数据中搜索指定标签的值。
     * 标签传入时使用十六进制字符串，如 "5A"、"5F24"。
     */
    fun findTag(data: Map<ByteArray, ByteArray>, tagHex: String): ByteArray? {
        val tagBytes = tagHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return data.entries.firstOrNull { it.key.contentEquals(tagBytes) }?.value
    }

    /**
     * 递归解析 TLV 数据。
     */
    private fun parseRecursive(
        data: ByteArray,
        offset: Int,
        end: Int,
        result: MutableMap<ByteArray, ByteArray>
    ): Int {
        var pos = offset
        while (pos < end) {
            if (pos >= data.size) break

            // 解析标签
            val tagStart = pos
            val firstByte = data[pos++].toInt() and 0xFF

            // 检查标签是否为 2 字节（低 5 位全为 1）
            if ((firstByte and 0x1F) == 0x1F) {
                if (pos < end) {
                    pos++ // 跳过第二个标签字节
                }
            }
            val tag = data.copyOfRange(tagStart, pos)

            if (pos >= end) break

            // 解析长度
            val lenFirst = data[pos++].toInt() and 0xFF
            val length: Int
            if (lenFirst < 0x80) {
                length = lenFirst
            } else if (lenFirst == 0x80) {
                // 不定长格式，不常见于 EMV 卡片数据
                // 回退并结束当前层级解析
                return pos - 1
            } else {
                val numLenBytes = lenFirst and 0x7F
                var l = 0
                repeat(numLenBytes) {
                    if (pos >= end) return pos
                    l = (l shl 8) or (data[pos++].toInt() and 0xFF)
                }
                length = l
            }

            if (pos + length > end) break

            // 解析值
            val value = data.copyOfRange(pos, pos + length)
            result[tag] = value

            // 检查是否为构造数据对象（constructed）
            val isConstructed = (firstByte and 0x20) != 0
            if (isConstructed) {
                // 递归解析内部 TLV
                parseRecursive(value, 0, value.size, result)
            }

            pos += length
        }
        return pos
    }

    /**
     * 将 BCD 编码的字节数组解码为十进制字符串。
     * 每个字节编码两个十进制数字（高 4 位和低 4 位）。
     */
    fun bcdToDecimal(bcd: ByteArray): String {
        return bcd.joinToString("") { byte ->
            val high = (byte.toInt() shr 4) and 0x0F
            val low = byte.toInt() and 0x0F
            "${high}${low}"
        }
    }

    /**
     * 在扁平化的 TLV 结果中搜索标签，递归查找嵌套的构造数据对象。
     * 返回找到的第一个匹配值。
     */
    fun findTagDeep(data: Map<ByteArray, ByteArray>, tagHex: String): ByteArray? {
        // 先直接查找
        findTag(data, tagHex)?.let { return it }
        // 在构造数据对象中递归查找
        for ((_, value) in data) {
            try {
                val nested = parse(value)
                findTagDeep(nested, tagHex)?.let { return it }
            } catch (_: Exception) {
                // 忽略无法解析的嵌套数据
            }
        }
        return null
    }
}
