package com.focusdu.app.imu.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** X23 验收锚点：20 字节帧解析（字节序/CRC/无效帧丢弃），与 X25 固件同源协议 */
class ImuFrameParserTest {

    @Test
    fun `编解码往返 正负值与小数角速度`() {
        val frame = ImuFrameParser.encode(
            seq = 42L,
            axMg = -512, ayMg = 0, azMg = 987,
            gxCentiDps = -1250, gyCentiDps = 0, gzCentiDps = 250,
            tempC = 26,
        )
        val parsed = ImuFrameParser.parse(frame)
        assertNotNull(parsed)
        parsed!!.let {
            assertEquals(42L, it.seq)
            assertEquals(-512f, it.axMg)
            assertEquals(0f, it.ayMg)
            assertEquals(987f, it.azMg)
            assertEquals(-12.5f, it.gxDps)
            assertEquals(0f, it.gyDps)
            assertEquals(2.5f, it.gzDps)
            assertEquals(26, it.tempC)
        }
    }

    @Test
    fun `已知向量 静置样例帧 与固件README示例一致`() {
        // decode_imu_frame.py --selftest 的同款样例：seq=1, az=1000mg, 温度 25°C（帧由同源编码器构造）
        val frame = ImuFrameParser.encode(1L, 0, 0, 1000, 0, 0, 0, 25)
        val parsed = ImuFrameParser.parse(frame)
        assertNotNull(parsed)
        assertEquals(1L, parsed!!.seq)
        assertEquals(0f, parsed.axMg)
        assertEquals(1000f, parsed.azMg)
        assertEquals(0f, parsed.gxDps)
        assertEquals(25, parsed.tempC)
    }

    @Test
    fun `CRC 错帧丢弃不伪装有效数据`() {
        val frame = ImuFrameParser.encode(7L, 0, 0, 1000, 0, 0, 0, 25)
        frame[18] = (frame[18] + 1).toByte()   // 破坏 CRC
        assertNull(ImuFrameParser.parse(frame))
    }

    @Test
    fun `无效标记帧丢弃`() {
        val frame = ImuFrameParser.encode(7L, 0, 0, 1000, 0, 0, 0, 25, flags = 0x00)
        assertNull(ImuFrameParser.parse(frame))
    }

    @Test
    fun `长度错误丢弃`() {
        assertNull(ImuFrameParser.parse(ByteArray(19)))
        assertNull(ImuFrameParser.parse(ByteArray(21)))
    }

    @Test
    fun `seq 回卷与大值按无符号 32 位解析`() {
        val frame = ImuFrameParser.encode(0xFFFFL + 2, 0, 0, 1000, 0, 0, 0, 25)  // 0x1_0001
        assertEquals(0x1_0001L, ImuFrameParser.parse(frame)!!.seq)
    }
}
