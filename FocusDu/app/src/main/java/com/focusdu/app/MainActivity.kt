package com.focusdu.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.focusdu.app.pomodoro.Phase
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.launch

/**
 * X02 规定的唯一 Activity：所有页面都是 Fragment，导航见 res/navigation/nav_graph.xml。
 * 附加职责：番茄相位变化时的自动引导（T3-04）——
 *   专注→休息：自动带到 P09 休息页；休息→结束：自动带到 P12 看报告。
 * 仅在相位实际变化时导航一次，用户手动导航不被打断。
 */
class MainActivity : AppCompatActivity() {

    private var lastPhase: Phase? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val navHost = supportFragmentManager.findFragmentById(R.id.nav_host) as NavHostFragment
        findViewById<MaterialToolbar>(R.id.toolbar).setupWithNavController(navHost.navController)

        // 番茄期间的相机绑定到本页面的生命周期（退到后台自动停流，回前台自动恢复）
        val container = (application as FocusDuApp).container
        container.sessionCamera.attachOwner(this)

        lifecycleScope.launch {
            container.pomodoroEngine.state.collect { s ->
                val prev = lastPhase
                lastPhase = s.phase
                if (prev == null || prev == s.phase) return@collect

                val nav = navHost.navController
                when {
                    s.phase == Phase.BREAK && prev == Phase.FOCUS ->
                        nav.navigate(R.id.p09RestPage)
                    s.phase == Phase.FINISHED && prev == Phase.BREAK ->
                        if (nav.currentDestination?.id != R.id.p12RoundReportPage) {
                            nav.navigate(R.id.p12RoundReportPage)
                        }
                }
            }
        }
    }
}
