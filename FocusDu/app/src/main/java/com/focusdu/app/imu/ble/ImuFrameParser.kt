package com.focusdu.app.imu.ble


/**
 * X23 BLE 20 字节帧解析（协议见 docs/接口协议IMU-v0.md 第 0 节 / firmware README）。
 * 纯函数无 Android 依赖，单测锚定字节序与校验口径（X25 固件同源实现，两端一致性靠这里保证）。
 *
 * 帧格式（小端）：seq u32 | ax,ay,az i16(毫g) | gx,gy,gz i16(0.01°/s) | temp i8 | flags u8 | crc8 | rsv
 * CRC-8 poly 0x07 init 0x00，覆盖字节 0–17；CRC 不符或 flags bit0=0 的帧丢弃（不伪装有效数据）。
 */
object ImuFrameParser {

    const val FRAME_LENGTH = 20
    const val FLAG_DATA_VALID = 0x01

    /** 解析成功的一帧（原始传感器值，未做特征计算） */
    data class ParsedFrame(
        val seq: Long,
        val axMg: Float,
        val ayMg: Float,
        val azMg: Float,
        val gxDps: Float,
        val gyDps: Float,
        val gzDps: Float,
        val tempC: Int,
    )

    /** 解析结果：null 表示帧无效（CRC 错/长度错/无效标记），调用方丢弃并计数 */
    fun parse(frame: ByteArray): ParsedFrame? {
        if (frame.size != FRAME_LENGTH) return null
        if (crc8(frame, 18) != (frame[18].toInt() and 0xFF)) return null
        if (frame[17].toInt() and FLAG_DATA_VALID == 0) return null

        fun i16(offset: Int): Int =
            (frame[offset].toInt() and 0xFF) or ((frame[offset + 1].toInt() and 0xFF) shl 8)

        fun i16Signed(offset: Int): Float = i16(offset).let { if (it >= 0x8000) it - 0x10000 else it }.toFloat()

        val seq = (frame[0].toLong() and 0xFF) or
            ((frame[1].toLong() and 0xFF) shl 8) or
            ((frame[2].toLong() and 0xFF) shl 16) or
            ((frame[3].toLong() and 0xFF) shl 24)

        return ParsedFrame(
            seq = seq,
            axMg = i16Signed(4),
            ayMg = i16Signed(6),
            azMg = i16Signed(8),
            gxDps = i16Signed(10) / 100f,
            gyDps = i16Signed(12) / 100f,
            gzDps = i16Signed(14) / 100f,
            tempC = frame[16].toInt(),
        )
    }

    /** CRC-8（poly 0x07，init 0x00），与固件 crc8() 同实现 */
    fun crc8(data: ByteArray, length: Int): Int {
        var crc = 0
        for (i in 0 until length) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 0x80 != 0) ((crc shl 1) xor 0x07) and 0xFF else (crc shl 1) and 0xFF
            }
        }
        return crc
    }

    /** 测试与联调工具用：按协议编码一帧（与固件打包逻辑同源） */
    fun encode(
        seq: Long,
        axMg: Int, ayMg: Int, azMg: Int,
        gxCentiDps: Int, gyCentiDps: Int, gzCentiDps: Int,
        tempC: Int,
        flags: Int = FLAG_DATA_VALID,
    ): ByteArray {
        val frame = ByteArray(FRAME_LENGTH)
        fun put16le(offset: Int, v: Int) {
            frame[offset] = (v and 0xFF).toByte()
            frame[offset + 1] = ((v shr 8) and 0xFF).toByte()
        }
        frame[0] = (seq and 0xFF).toByte()
        frame[1] = ((seq shr 8) and 0xFF).toByte()
        frame[2] = ((seq shr 16) and 0xFF).toByte()
        frame[3] = ((seq shr 24) and 0xFF).toByte()
        put16le(4, axMg); put16le(6, ayMg); put16le(8, azMg)
        put16le(10, gxCentiDps); put16le(12, gyCentiDps); put16le(14, gzCentiDps)
        frame[16] = tempC.toByte()
        frame[17] = flags.toByte()
        frame[18] = crc8(frame, 18).toByte()
        frame[19] = 0
        return frame
    }
}
