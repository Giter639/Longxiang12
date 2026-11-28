package com.focusdu.app.imu.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.focusdu.app.core.MonotonicClock
import com.focusdu.app.imu.ImuEngine
import com.focusdu.app.imu.ImuSample
import com.focusdu.app.imu.ImuState
import com.focusdu.app.imu.ImuStateClassifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * X23 BLE IMU 真引擎：扫描 FocusDu-IMU → GATT 连接 → 订阅 20 字节帧 Notify →
 * [ImuFrameParser] 解包 → [ImuStateClassifier]（X24 判定层）→ 产出判定态样本流。
 *
 * 协议对齐 docs/接口协议IMU-v0.md：App 侧补单调钟时间戳；CRC/无效帧丢弃并计数（不伪装有效数据）；
 * 断连自动重连（指数退避，上限 5 次）；时间倒退由 classifier 自动复位（新会话）。
 *
 * 状态机 [Status] 供 P15 设备绑定页展示；权限缺失不崩溃，status=NeedsPermission 等页面授权后
 * 调 [retryConnection]。线程模型：GATT 回调在 binder 线程，统一投回 scope 主线程处理（classifier 非线程安全）。
 */
class BleImuEngine(
    private val context: Context,
    private val clock: MonotonicClock,
    private val scope: CoroutineScope,
) : ImuEngine {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("6f0d0001-5a2e-4c1e-9b3a-46a5d2f10e01")
        val DATA_CHAR_UUID: UUID = UUID.fromString("6f0d0002-5a2e-4c1e-9b3a-46a5d2f10e01")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val DEVICE_NAME = "FocusDu-IMU"
        private const val TAG = "FocusDuImu"
        private const val SCAN_PERIOD_MS = 15_000L
        private const val RECONNECT_MAX_ATTEMPTS = 5
        private const val RECONNECT_BASE_MS = 2_000L
    }

    sealed interface Status {
        data object Idle : Status
        data object NeedsPermission : Status
        data object Scanning : Status
        data object ScanTimeout : Status
        data class Connecting(val deviceName: String) : Status
        data class Connected(val deviceName: String) : Status
        data class Reconnecting(val deviceName: String, val attempt: Int) : Status
        data class Disconnected(val deviceName: String) : Status
        data class Error(val message: String) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status

    private val flow = MutableSharedFlow<ImuSample>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val classifier = ImuStateClassifier()
    private var job: Job? = null

    private val bluetoothAdapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var gatt: BluetoothGatt? = null
    private var scanCallback: ScanCallback? = null
    private var reconnectAttempts = 0
    private var badFrames = 0          // CRC 错/无效帧计数（协议第 0 节：丢弃并计数）
    private var notifying = false

    override fun samples(): Flow<ImuSample> {
        ensureStarted()
        return flow
    }

    override fun stop() {
        teardown()
        _status.value = Status.Idle
    }

    /** 页面授权后/扫描超时后手动重试：重置状态机重新走一遍连接流程 */
    fun retryConnection() {
        teardown()
        _status.value = Status.Idle
        ensureStarted()
    }

    /** 用户主动断开（P15 按钮）：关闭 GATT 并回到空闲 */
    fun disconnect() {
        teardown()
        _status.value = Status.Idle
    }

    /** 需要用户授予的运行时权限（12+ 蓝牙权限 / 11- 定位权限），P15 据此发起请求 */
    fun requiredPermissions(): List<String> = if (Build.VERSION.SDK_INT >= 31) {
        listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun hasBlePermissions(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensureStarted() {
        if (job?.isActive == true) return
        job = scope.launch { connectFlow() }
    }

    private suspend fun connectFlow() {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            _status.value = Status.Error("蓝牙不可用或未开启")
            return
        }
        if (!hasBlePermissions()) {
            _status.value = Status.NeedsPermission
            return
        }

        _status.value = Status.Scanning
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                stopScan()
                scope.launch { connectTo(result.device) }
            }

            override fun onScanFailed(errorCode: Int) {
                _status.value = Status.Error("扫描失败（错误码 $errorCode）")
            }
        }
        scanCallback = callback
        try {
            adapter.bluetoothLeScanner.startScan(
                listOf(ScanFilter.Builder().setServiceUuid(android.os.ParcelUuid(SERVICE_UUID)).build()),
                ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build(),
                callback,
            )
        } catch (e: SecurityException) {
            _status.value = Status.NeedsPermission
            return
        }
        delay(SCAN_PERIOD_MS)
        if (_status.value is Status.Scanning) {
            stopScan()
            _status.value = Status.ScanTimeout
        }
    }

    private fun connectTo(device: BluetoothDevice) {
        val name = runCatching { device.name ?: DEVICE_NAME }.getOrDefault(DEVICE_NAME)
        _status.value = Status.Connecting(name)
        try {
            gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            _status.value = Status.NeedsPermission
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            scope.launch {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        reconnectAttempts = 0
                        notifying = false
                        g.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        notifying = false
                        val name = currentDeviceName()
                        if (reconnectAttempts >= RECONNECT_MAX_ATTEMPTS) {
                            _status.value = Status.Disconnected(name)
                        } else {
                            reconnectAttempts++
                            _status.value = Status.Reconnecting(name, reconnectAttempts)
                            delay(RECONNECT_BASE_MS * reconnectAttempts)
                            runCatching { g.connect() }
                                .onFailure { _status.value = Status.Disconnected(name) }
                        }
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            scope.launch {
                val name = currentDeviceName()
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    _status.value = Status.Error("服务发现失败（$name）")
                    return@launch
                }
                val characteristic = g.getService(SERVICE_UUID)?.getCharacteristic(DATA_CHAR_UUID)
                if (characteristic == null) {
                    _status.value = Status.Error("固件缺少协议服务（$name），检查 X25 固件版本")
                    return@launch
                }
                runCatching { g.setCharacteristicNotification(characteristic, true) }
                val cccd = characteristic.getDescriptor(CCCD_UUID)
                if (cccd == null) {
                    _status.value = Status.Error("缺少 Notify 描述符（$name）")
                    return@launch
                }
                runCatching {
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(cccd)
                }.onFailure {
                    _status.value = Status.Error("订阅特征值失败（$name）")
                }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            scope.launch {
                if (status == BluetoothGatt.GATT_SUCCESS &&
                    descriptor.uuid == CCCD_UUID
                ) {
                    notifying = true
                    _status.value = Status.Connected(currentDeviceName())
                } else if (status != BluetoothGatt.GATT_SUCCESS) {
                    _status.value = Status.Error("订阅未完成（错误码 $status）")
                }
            }
        }

        // API 33+ 走新回调；旧系统走 deprecated 重载。两处都汇到同一帧处理入口
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleFrame(value)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            handleFrame(characteristic.value)
        }
    }

    /** 帧入口：解析 → 判定 → 发样本流；坏帧丢弃并计数（主线程串行，classifier 非线程安全） */
    private fun handleFrame(bytes: ByteArray) {
        val parsed = ImuFrameParser.parse(bytes)
        if (parsed == null) {
            badFrames++
            if (badFrames % 250 == 1) Log.w(TAG, "bad IMU frames: $badFrames")
            return
        }
        scope.launch {
            if (!notifying) return@launch
            val raw = ImuSample(
                tsMs = clock.nowMs(),          // 协议第 1 节：App 侧接收时刻（单调钟）
                seq = parsed.seq,
                axMg = parsed.axMg, ayMg = parsed.ayMg, azMg = parsed.azMg,
                gxDps = parsed.gxDps, gyDps = parsed.gyDps, gzDps = parsed.gzDps,
                pitchDeg = 0f, rollDeg = 0f, motionRms = 0f,
                state = ImuState.NORMAL, conf = 0f,   // 占位：由 X24 判定层覆盖
            )
            flow.tryEmit(classifier.classify(raw))
        }
    }

    private fun currentDeviceName(): String = when (val s = _status.value) {
        is Status.Connecting -> s.deviceName
        is Status.Connected -> s.deviceName
        is Status.Reconnecting -> s.deviceName
        is Status.Disconnected -> s.deviceName
        else -> DEVICE_NAME
    }

    private fun stopScan() {
        scanCallback?.let { cb ->
            runCatching { bluetoothAdapter?.bluetoothLeScanner?.stopScan(cb) }
        }
        scanCallback = null
    }

    private fun teardown() {
        job?.cancel()
        job = null
        stopScan()
        runCatching { gatt?.close() }
        gatt = null
        notifying = false
        reconnectAttempts = 0
        classifier.reset()
    }
}
