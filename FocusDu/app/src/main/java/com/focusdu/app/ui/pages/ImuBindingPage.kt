package com.focusdu.app.ui.pages

import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.focusdu.app.R
import com.focusdu.app.imu.ble.BleImuEngine
import com.focusdu.app.ui.common.BasePageFragment
import kotlinx.coroutines.launch

/**
 * P15 设备绑定页（X23）：授权蓝牙权限 → 扫描并自动连接 FocusDu-IMU → 展示连接状态。
 * 只做设备通道管理，不展示姿态数据（P07/P05 已有状态视图）。
 */
class P15ImuBindingPage : BasePageFragment() {
    override val ownerTag = "R3 X23 · BLE 取流与设备绑定"
    override val hintText = "按服务 UUID 过滤扫描，仅连接 FocusDu-IMU（协议见 docs/接口协议IMU-v0.md）。断连自动重连；固件烧录与验收见 firmware/esp32_focusdu_imu/README.md。"

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants.values.all { it }) vm.imuRetryConnection()
        }

    override fun onPageReady(view: View) {
        addButton(getString(R.string.p15_request_permission)) {
            val needed = vm.imuRequiredPermissions()
            if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
        }
        addButton(getString(R.string.p15_rescan)) { vm.imuRetryConnection() }
        addButton(getString(R.string.p15_disconnect)) { vm.imuDisconnect() }

        viewLifecycleOwner.lifecycleScope.launch {
            vm.imuEngineStatus?.collect { status ->
                binding.pageStatus.text = buildString {
                    append(getString(R.string.p15_mode_fmt, vm.modeLabelForImu()))
                    append('\n')
                    append(
                        when (status) {
                            is BleImuEngine.Status.Idle -> getString(R.string.p15_status_idle)
                            is BleImuEngine.Status.NeedsPermission -> getString(R.string.p15_status_needs_permission)
                            is BleImuEngine.Status.Scanning -> getString(R.string.p15_status_scanning)
                            is BleImuEngine.Status.ScanTimeout -> getString(R.string.p15_status_scan_timeout)
                            is BleImuEngine.Status.Connecting -> getString(R.string.p15_status_connecting, status.deviceName)
                            is BleImuEngine.Status.Connected -> getString(R.string.p15_status_connected, status.deviceName)
                            is BleImuEngine.Status.Reconnecting -> getString(
                                R.string.p15_status_reconnecting, status.deviceName, status.attempt,
                            )
                            is BleImuEngine.Status.Disconnected -> getString(R.string.p15_status_disconnected, status.deviceName)
                            is BleImuEngine.Status.Error -> getString(R.string.p15_status_error, status.message)
                        },
                    )
                }
            }
        }
    }
}
