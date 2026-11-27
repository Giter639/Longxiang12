package com.focusdu.app.imu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** X24 验收锚点：特征解算符号、滞回回差、保持窗、基线偏移、五态优先级（改判定行为必须同步改这里） */
class ImuStateClassifierTest {

    /** 保持窗调小、低通关掉（α=1 直接落位）隔离状态机逻辑；进入/退出阈值保持真机口径 */
    private fun classifier(baseline: ImuBaseline? = null) = ImuStateClassifier(
        ImuStateClassifier.Config(
            gravityAlpha = 1f, postureHoldMs = 120, fidgetHoldMs = 80, awayHoldMs = 200,
        ),
    ).also { it.setBaseline(baseline) }

    private fun raw(tsMs: Long, axMg: Float = 0f, ayMg: Float = 0f, azMg: Float = 1000f) = ImuSample(
        tsMs = tsMs, seq = tsMs / 40,
        axMg = axMg, ayMg = ayMg, azMg = azMg,
        gxDps = 0f, gyDps = 0f, gzDps = 0f,
        pitchDeg = 0f, rollDeg = 0f, motionRms = 0f,
        state = ImuState.NORMAL, conf = 0.5f,   // 原始样本未判定，占位值
    )

    /** 从 fromMs 起按 40ms 步进喂 count 个相同样本，返回全部输出 */
    private fun feed(
        c: ImuStateClassifier, fromMs: Long, count: Int,
        axMg: Float = 0f, ayMg: Float = 0f, azMg: Float = 1000f,
    ): List<ImuSample> = (0 until count).map { c.classify(raw(fromMs + it * 40, axMg, ayMg, azMg)) }

    @Test
    fun `静止竖直样本判正常且特征归零`() {
        val out = feed(classifier(), 0, 3)
        assertTrue(out.all { it.state == ImuState.NORMAL })
        assertTrue(out.all { kotlin.math.abs(it.pitchDeg) < 1f && kotlin.math.abs(it.rollDeg) < 1f })
        assertTrue(out.all { it.motionRms < 0.05f })
        assertTrue(out.all { it.conf >= 0.5f })
    }

    @Test
    fun `低头30度持续保持窗后切换 转换期置信度低`() {
        val out = feed(classifier(), 0, 5, axMg = 500f, azMg = 866f)   // atan2(0.5, 0.866) = 30°
        assertEquals(listOf(ImuState.NORMAL, ImuState.NORMAL, ImuState.NORMAL, ImuState.HEAD_DOWN, ImuState.HEAD_DOWN),
            out.map { it.state })
        assertEquals(0.60f, out[1].conf)   // 转换期
        assertEquals(0.90f, out[4].conf)   // 稳定期
        assertTrue(out.last().pitchDeg > 29f && out.last().pitchDeg < 31f)
    }

    @Test
    fun `保持窗不足不切换`() {
        val out = feed(classifier(), 0, 2, axMg = 500f, azMg = 866f)   // 只 80ms < 120ms 保持窗
        assertTrue(out.all { it.state == ImuState.NORMAL })
    }

    @Test
    fun `滞回回差 20度进不去也不退出`() {
        val c = classifier()
        // 20°（介于退出 15 与进入 25 之间）从正常态进入不了
        feed(c, 0, 5, axMg = 342f, azMg = 940f)
        assertEquals(ImuState.NORMAL, c.classify(raw(200, axMg = 342f, azMg = 940f)).state)
        // 已处于低头（35° 推入）后回到 20°：≥ 退出阈值 15°，滞回保持低头不抖动
        feed(c, 240, 5, axMg = 574f, azMg = 819f)
        assertEquals(ImuState.HEAD_DOWN, c.classify(raw(440, axMg = 342f, azMg = 940f)).state)
        // 回到 10°（< 退出 15°）才恢复
        assertEquals(ImuState.NORMAL, c.classify(raw(480, axMg = 174f, azMg = 985f)).state)
    }

    @Test
    fun `基线抬高后同姿态不再判低头`() {
        val c = classifier(ImuBaseline(pitchDeg = 10f, rollDeg = 0f, motionRms = 0f))
        // 物理俯仰 30°，基线 10° → 偏移 20° < 25°：不判低头
        feed(c, 0, 5, axMg = 500f, azMg = 866f)
        assertEquals(ImuState.NORMAL, c.classify(raw(200, axMg = 500f, azMg = 866f)).state)
        // 40° → 偏移 30° ≥ 25°：判低头
        feed(c, 240, 4, axMg = 643f, azMg = 766f)
        assertEquals(ImuState.HEAD_DOWN, c.classify(raw(400, axMg = 643f, azMg = 766f)).state)
    }

    @Test
    fun `侧趴35度判侧趴`() {
        val out = feed(classifier(), 0, 5, ayMg = 574f, azMg = 819f)   // atan2(0.574, 0.819) = 35°
        assertTrue(out.last().state == ImuState.LEAN)
        assertTrue(out.last().rollDeg > 34f && out.last().rollDeg < 36f)
    }

    @Test
    fun `优先级 低头压过乱动`() {
        // 姿态 49°（低头）+ 合加速度偏离 0.32（乱动区间）：取更高优先级
        val out = feed(classifier(), 0, 5, axMg = 1000f, azMg = 866f)
        assertTrue(out.last().motionRms > 0.25f)   // 乱动条件确实满足
        assertEquals(ImuState.HEAD_DOWN, out.last().state)
    }

    @Test
    fun `优先级 离座压过低头`() {
        // 低头 + 走动级能量（偏离 0.41 ≥ 离座进入 0.35）
        val out = feed(classifier(), 0, 6, axMg = 500f, ayMg = 1000f, azMg = 866f)
        assertTrue(out.last().motionRms > 0.35f)
        assertEquals(ImuState.AWAY, out.last().state)
    }

    @Test
    fun `持续走动判离座 归位恢复 回差防误退`() {
        val c = classifier()
        // 走动：|a|=1.5g 持续 ≥ awayHold
        feed(c, 0, 6, azMg = 1500f)
        assertEquals(ImuState.AWAY, c.classify(raw(240, azMg = 1500f)).state)
        // 回差：偏离 0.2（≥ 退出 0.10）时保持离座，不抖回正常
        assertEquals(ImuState.AWAY, c.classify(raw(280, azMg = 1200f)).state)
        // 静止需持续约一个滑窗（12 样本 ≈480ms）把走动样本泄掉，motionRms 降至 <0.10 才恢复
        val quiet = feed(c, 320, 12)
        assertEquals(ImuState.AWAY, quiet[3].state)      // 窗内还有走动样本，不恢复
        assertEquals(ImuState.NORMAL, quiet.last().state)
    }

    @Test
    fun `重力低通对姿态阶跃渐进收敛 不突变`() {
        val c = ImuStateClassifier()   // 真机口径 α=0.05
        feed(c, 0, 2)                  // 先静置初始化
        // 阶跃到 30°：滤波后首帧只偏一小步，不瞬间到位
        val first = c.classify(raw(80, axMg = 500f, azMg = 866f))
        assertTrue(first.pitchDeg in 0.5f..10f)
        // 8s（200 样本）后收敛到 30° 附近
        val settled = feed(c, 120, 200, axMg = 500f, azMg = 866f).last()
        assertTrue(settled.pitchDeg in 29.5f..30.5f)
    }

    @Test
    fun `断连时间倒退自动复位 重新积累保持窗`() {
        val c = classifier()
        feed(c, 0, 5, axMg = 500f, azMg = 866f)            // 已处于低头
        assertEquals(ImuState.HEAD_DOWN, c.classify(raw(240, axMg = 500f, azMg = 866f)).state)
        // 时间倒退 = 新会话：复位后单帧 tilted 只能是转换期（pending 重新计时）
        val afterReconnect = c.classify(raw(0, axMg = 500f, azMg = 866f))
        assertEquals(ImuState.NORMAL, afterReconnect.state)
        assertEquals(0.60f, afterReconnect.conf)
    }
}
