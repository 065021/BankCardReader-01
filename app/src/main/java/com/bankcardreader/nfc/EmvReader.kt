package com.bankcardreader.nfc

import android.nfc.tech.IsoDep
import java.io.IOException

/**
 * EMV 银行卡数据读取器。
 * 通过 ISO-DEP (ISO 14443-4) 协议与银行卡通信，
 * 读取 PAN（卡号）、有效期、卡组织等信息。
 */
object EmvReader {

    /** PPSE 应用名：2PAY.SYS.DDF01 */
    private val PPSE_NAME = "2PAY.SYS.DDF01".toByteArray(Charsets.US_ASCII)

    /** 已知支付应用的 AID，按优先级排序 */
    private val KNOWN_AIDS = listOf(
        "A0000000031010" to "Visa",
        "A0000000032010" to "Visa Electron",
        "A0000000038010" to "Visa",
        "A0000000041010" to "Mastercard",
        "A0000000043060" to "Mastercard Maestro",
        "A00000002501"   to "American Express",
        "A0000003330101" to "UnionPay",
        "A0000000651010" to "JCB",
        "A0000001523010" to "Discover",
        "A0000002771010" to "Interac"
    )

    /**
     * 读取结果。
     */
    data class EmvCardData(
        val pan: String = "",
        val expiryDate: String = "",
        val cardScheme: String = "",
        val holderName: String = "",
        val aid: String = ""
    )

    /**
     * 读取银行卡信息，返回 [EmvCardData]。
     * @throws IOException 通信失败时抛出
     * @throws IllegalStateException 卡片不支持 EMV 时抛出
     */
    @Throws(IOException::class, IllegalStateException::class)
    fun readCard(isoDep: IsoDep): EmvCardData {
        isoDep.timeout = 5000

        if (!isoDep.isConnected) {
            isoDep.connect()
        }

        try {
            // 步骤 1：选择 PPSE
            val ppseResponse = selectPPSE(isoDep)

            // 步骤 2：从 PPSE 响应中提取 AID 列表
            var aids = extractAids(ppseResponse)

            // 如果 PPSE 没有返回 AID，使用已知的 AID 列表
            if (aids.isEmpty()) {
                aids = KNOWN_AIDS.map { (aidHex, _) -> hexToBytes(aidHex) }
            }

            // 步骤 3：依次尝试每个 AID
            for (aid in aids) {
                try {
                    return readWithAid(isoDep, aid)
                } catch (e: Exception) {
                    // 尝试下一个 AID
                    continue
                }
            }

            throw IllegalStateException("无法读取卡片数据：所有 AID 均失败")

        } finally {
            try {
                isoDep.close()
            } catch (_: Exception) {
                // 忽略关闭异常
            }
        }
    }

    /**
     * 使用指定 AID 读取卡片。
     */
    @Throws(IOException::class)
    private fun readWithAid(isoDep: IsoDep, aid: ByteArray): EmvCardData {
        // 选择支付应用
        val selectResponse = selectAid(isoDep, aid)

        // 解析 FCI 模板，获取 PDOL 信息
        val fciData = TlvParser.parse(selectResponse)
        val pdolData = TlvParser.findTagDeep(fciData, "9F38")

        // 发送 GPO 命令
        val gpoResponse = sendGpo(isoDep, pdolData)

        // 解析 GPO 响应，获取 AFL 和 AIP
        val gpoData = TlvParser.parse(gpoResponse)
        val afl = TlvParser.findTag(gpoData, "94")
            ?: throw IOException("GPO 响应中缺少 AFL")

        // 根据 AFL 读取记录
        val records = readAflRecords(isoDep, afl)

        // 从记录中提取卡数据
        return extractCardData(records, aid)
    }

    /**
     * 选择 PPSE（Proximity Payment System Environment）。
     */
    @Throws(IOException::class)
    private fun selectPPSE(isoDep: IsoDep): ByteArray {
        val command = buildSelectApdu(PPSE_NAME)
        val response = isoDep.transceive(command)
        checkSw(response)
        return response
    }

    /**
     * 选择指定 AID 的支付应用。
     */
    @Throws(IOException::class)
    private fun selectAid(isoDep: IsoDep, aid: ByteArray): ByteArray {
        val command = buildSelectApdu(aid)
        val response = isoDep.transceive(command)
        checkSw(response)
        return response
    }

    /**
     * 发送 GPO（Get Processing Options）命令。
     */
    @Throws(IOException::class)
    private fun sendGpo(isoDep: IsoDep, pdolData: ByteArray?): ByteArray {
        val gpoData = if (pdolData != null && pdolData.isNotEmpty()) {
            buildPdolData(pdolData)
        } else {
            byteArrayOf(0x83.toByte(), 0x00) // 空 PDOL 数据
        }

        val command = buildApdu(0x80.toByte(), 0xA8.toByte(), 0x00, 0x00, gpoData)
        val response = isoDep.transceive(command)
        checkSw(response)
        return response
    }

    /**
     * 根据 AFL（Application File Locator）读取记录。
     * AFL 每 4 字节一组：[SFI(5bits)|起始记录(8bits)|结束记录(8bits)|认证记录数(8bits)]
     */
    @Throws(IOException::class)
    private fun readAflRecords(isoDep: IsoDep, afl: ByteArray): MutableList<ByteArray> {
        val records = mutableListOf<ByteArray>()
        var pos = 0

        while (pos + 4 <= afl.size) {
            val sfi = (afl[pos].toInt() shr 3) and 0x1F
            val startRecord = afl[pos + 1].toInt() and 0xFF
            val endRecord = afl[pos + 2].toInt() and 0xFF
            // afl[pos + 3] 是离线数据认证记录数，本应用忽略

            for (recordNum in startRecord..endRecord) {
                val p2 = (sfi shl 3) or 0x04
                // READ RECORD: CLA=00, INS=B2, P1=record_number, P2=(SFI<<3)|4
                val command = buildApdu(
                    0x00.toByte(), 0xB2.toByte(),
                    recordNum, p2,
                    byteArrayOf()
                )
                val response = isoDep.transceive(command)

                // 状态字 6A83 表示记录不存在，跳过
                val sw = getSw(response)
                if (sw == 0x6A83) continue

                checkSw(response)
                records.add(response)
            }

            pos += 4
        }

        return records
    }

    /**
     * 从记录中提取卡片数据。
     */
    private fun extractCardData(
        records: List<ByteArray>,
        aid: ByteArray
    ): EmvCardData {
        val allData = mutableMapOf<ByteArray, ByteArray>()

        for (record in records) {
            // 去掉末尾的状态字（2 字节）
            val recordData = if (record.size >= 2) {
                record.copyOfRange(0, record.size - 2)
            } else {
                record
            }
            try {
                allData.putAll(TlvParser.parse(recordData))
            } catch (_: Exception) {
                // 跳过无法解析的记录
            }
        }

        // 提取 PAN（标签 5A）
        val panBytes = TlvParser.findTag(allData, "5A")
        val pan = if (panBytes != null) {
            val panStr = TlvParser.bcdToDecimal(panBytes)
            // 去除末尾的 F 填充
            panStr.trimEnd('F')
        } else {
            // 尝试从 Track 2 Equivalent Data（标签 57）中提取
            val track2 = TlvParser.findTag(allData, "57")
            if (track2 != null) {
                extractPanFromTrack2(track2)
            } else ""
        }

        // 提取有效期（标签 5F24）
        val expiryBytes = TlvParser.findTag(allData, "5F24")
        val expiryDate = if (expiryBytes != null && expiryBytes.size >= 2) {
            val year = ((expiryBytes[0].toInt() shr 4) and 0x0F) * 10 +
                       (expiryBytes[0].toInt() and 0x0F)
            val month = ((expiryBytes[1].toInt() shr 4) and 0x0F) * 10 +
                        (expiryBytes[1].toInt() and 0x0F)
            "${month.toString().padStart(2, '0')}/${year.toString().padStart(2, '0')}"
        } else ""
        val cardScheme = identifyScheme(aid)
        val holderBytes = TlvParser.findTag(allData, "5F20")
        val holderName = holderBytes?.let { String(it, Charsets.US_ASCII).trim() } ?: ""

        val aidHex = aid.joinToString("") { "%02X".format(it) }

        return EmvCardData(
            pan = formatPan(pan),
            expiryDate = expiryDate,
            cardScheme = cardScheme,
            holderName = holderName,
            aid = aidHex
        )
    }

    /**
     * 格式化 PAN：4 位一组。
     */
    private fun formatPan(pan: String): String {
        return pan.chunked(4).joinToString(" ")
    }

    /**
     * 从 Track 2 数据中提取 PAN。
     * Track 2 格式：PAN | 'D' | 有效期 | 服务码 | 其他
     */
    private fun extractPanFromTrack2(track2: ByteArray): String {
        val track2Str = TlvParser.bcdToDecimal(track2)
        val separatorIndex = track2Str.indexOf('D')
        return if (separatorIndex > 0) {
            track2Str.substring(0, separatorIndex)
        } else {
            // 没有找到分隔符，取前 16 或 19 位
            val panLen = if (track2Str.length >= 19) 19 else 16
            track2Str.take(panLen)
        }
    }

    /**
     * 根据 AID 识别卡组织。
     */
    private fun identifyScheme(aid: ByteArray): String {
        for ((aidHex, scheme) in KNOWN_AIDS) {
            val knownAid = hexToBytes(aidHex)
            if (aid.size >= knownAid.size) {
                val prefix = aid.copyOf(knownAid.size)
                if (prefix.contentEquals(knownAid)) return scheme
            }
        }
        return "未知"
    }

    /**
     * 从 PPSE 响应中提取所有 AID。
     */
    private fun extractAids(response: ByteArray): List<ByteArray> {
        val data = response.copyOfRange(0, response.size - 2) // 去掉 SW
        val result = mutableListOf<ByteArray>()

        try {
            val tlvData = TlvParser.parse(data)
            // 递归查找标签 4F（Application Identifier / AID）
            findAidsRecursive(tlvData, result)
        } catch (_: Exception) {
            // 解析失败，返回空列表
        }

        return result
    }

    /**
     * 递归查找 AID（标签 0x4F）。
     */
    private fun findAidsRecursive(
        data: Map<ByteArray, ByteArray>,
        result: MutableList<ByteArray>
    ) {
        val aidTag = byteArrayOf(0x4F.toByte())
        for ((tag, value) in data) {
            if (tag.contentEquals(aidTag)) {
                result.add(value)
            }
            // 尝试解析嵌套的构造数据
            try {
                val nested = TlvParser.parse(value)
                findAidsRecursive(nested, result)
            } catch (_: Exception) {
                // 跳过
            }
        }
    }

    /**
     * 构建 PDOL 数据。
     * 根据卡要求的 PDOL 条目提供默认终端数据。
     */
    private fun buildPdolData(pdol: ByteArray): ByteArray {
        val writer = ByteArrayWriter()
        var pos = 0

        while (pos < pdol.size) {
            if (pos >= pdol.size) break

            val tagFirst = pdol[pos].toInt() and 0xFF
            val tagBytes: Int
            if (pos + 1 < pdol.size && (tagFirst and 0x1F) == 0x1F) {
                tagBytes = 2
            } else {
                tagBytes = 1
            }
            val tagHex = pdol.copyOfRange(pos, pos + tagBytes)
                .joinToString("") { "%02X".format(it) }
            pos += tagBytes

            if (pos >= pdol.size) break
            val length = pdol[pos].toInt() and 0xFF
            pos++

            // 根据标签提供默认值
            val defaultValue = getPdolDefault(tagHex, length)
            writer.writeBytes(defaultValue)
        }

        // 包装在标签 83 中
        val rawData = writer.toByteArray()
        val result = ByteArrayWriter()
        result.write(0x83.toByte())
        if (rawData.size < 0x80) {
            result.write(rawData.size.toByte())
        } else {
            result.write((0x80 or 0x01).toByte())
            result.write(rawData.size.toByte())
        }
        result.writeBytes(rawData)
        return result.toByteArray()
    }

    /**
     * 获取 PDOL 标签的默认值。
     */
    private fun getPdolDefault(tagHex: String, length: Int): ByteArray {
        return when (tagHex) {
            "9F66" -> { // Terminal Transaction Qualifiers
                byteArrayOf(0x26.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte())
            }
            "9F02" -> ByteArray(6) // Amount, Authorised (zero)
            "9F03" -> ByteArray(6) // Amount, Other (zero)
            "9F1A" -> byteArrayOf(0x01.toByte(), 0x56.toByte()) // Terminal Country Code (China=0156)
            "95"   -> ByteArray(5) // Terminal Verification Results
            "5F2A" -> byteArrayOf(0x01.toByte(), 0x56.toByte()) // Transaction Currency Code (CNY=156)
            "9A"   -> byteArrayOf(0x26.toByte(), 0x06.toByte(), 0x13.toByte()) // Transaction Date
            "9C"   -> byteArrayOf(0x00) // Transaction Type
            "9F37" -> byteArrayOf(0x00, 0x00, 0x00, 0x00) // Unpredictable Number
            "9F35" -> byteArrayOf(0x22.toByte()) // Terminal Type
            "9F40" -> ByteArray(5) // Additional Terminal Capabilities
            "9F33" -> byteArrayOf(0x60.toByte(), 0x00.toByte(), 0x00.toByte()) // Terminal Capabilities
            "9F1E" -> byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00) // IFD Serial
            "DF8118" -> ByteArray(24) // Mag Stripe CVM List
            "DF8119" -> ByteArray(4) // Mag Stripe Application Version Number
            else    -> ByteArray(length) // 未知标签用零填充
        }.let { default ->
            // 确保长度匹配
            if (default.size == length) default
            else default.copyOf(length)
        }
    }

    // ─── 工具方法 ───────────────────────────────────────────

    /**
     * 构建 SELECT APDU 命令。
     */
    private fun buildSelectApdu(data: ByteArray): ByteArray {
        // CLA=00, INS=A4, P1=04 (by DF name), P2=00 (first occurrence, return FCI)
        return buildApdu(0x00.toByte(), 0xA4.toByte(), 0x04, 0x00, data)
    }

    /**
     * 构建 APDU 命令。
     */
    private fun buildApdu(
        cla: Byte, ins: Byte, p1: Int, p2: Int, data: ByteArray
    ): ByteArray {
        val writer = ByteArrayWriter()
        writer.write(cla, ins, p1.toByte(), p2.toByte())
        if (data.isNotEmpty()) {
            writer.write(data.size.toByte())
            writer.writeBytes(data)
        }
        writer.write(0x00.toByte()) // Le
        return writer.toByteArray()
    }

    /**
     * 检查 APDU 响应的状态字是否为 9000（成功）。
     */
    @Throws(IOException::class)
    private fun checkSw(response: ByteArray) {
        val sw = getSw(response)
        if (sw != 0x9000) {
            throw IOException("APDU 命令失败，状态字: %04X".format(sw))
        }
    }

    /**
     * 获取 APDU 响应的状态字。
     */
    private fun getSw(response: ByteArray): Int {
        if (response.size < 2) return 0
        val sw1 = response[response.size - 2].toInt() and 0xFF
        val sw2 = response[response.size - 1].toInt() and 0xFF
        return (sw1 shl 8) or sw2
    }

    /**
     * 将十六进制字符串转换为字节数组。
     */
    private fun hexToBytes(hex: String): ByteArray {
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
