# FocusDu IMU 固件（X25）—— ESP32 + MPU6050

按 X22 接口协议（`FocusDu/app/src/main/java/com/focusdu/app/imu/ImuModels.kt`）上报 BLE 数据，
供 App 端 X23（BLE ImuStreamer）接入。固件只负责采集与传输，姿态角/五态判定在 App 端。

## 接线

| MPU6050 | ESP32 |
| --- | --- |
| VCC | 3V3 |
| GND | GND（AD0 也接 GND → 地址 0x68） |
| SDA | GPIO21 |
| SCL | GPIO22 |

板载 LED（GPIO2）：客户端已连接时常亮，断连熄灭并自动恢复广播。

## 烧录（Arduino IDE）

1. 装 **ESP32 Arduino 核心**（开发板管理器搜 esp32，2.x 或 3.x 均可）；本固件只用内置库（BLE/Wire），无需额外装库；
2. 开发板选 **ESP32 Dev Module**，打开 `esp32_focusdu_imu.ino` 直接烧录；
3. 串口监视器 115200：看到 `FocusDu-IMU advertising, 25Hz frames on DATA char` 即就绪；
   `WARN: MPU WHO_AM_I=...` 多为克隆芯片或接线/AD0 问题，先查线再怀疑代码。

## BLE 协议 v0（X23 App 端对接依据，两端同步改）

- 设备名：`FocusDu-IMU`；按服务 UUID 过滤扫描。
- 服务 `6F0D0001-5A2E-4C1E-9B3A-46A5D2F10E01`
  - 数据特征 `6F0D0002-…`：Notify，20 字节定长帧，25Hz，默认 MTU 23 恰好容纳；
  - 控制特征 `6F0D0003-…`：Write，v0 保留（采样率/量程配置 v1 开放）。

### 帧格式（小端）

| 字节 | 字段 | 含义 |
| --- | --- | --- |
| 0–3 | seq u32 | 递增帧号（断连查漏；固件重启归零，App 据此对齐） |
| 4–5 | ax i16 | 加速度 X，单位毫g（±2g 量程，16384 LSB/g） |
| 6–7 | ay i16 | 加速度 Y，毫g |
| 8–9 | az i16 | 加速度 Z，毫g（静止水平 ≈ +1000） |
| 10–11 | gx i16 | 角速度 X，单位 0.01°/s（±250°/s 量程，131 LSB/°/s） |
| 12–13 | gy i16 | 角速度 Y，0.01°/s |
| 14–15 | gz i16 | 角速度 Z，0.01°/s |
| 16 | temp i8 | 芯片温度 °C（MPU6050 内置，raw/340+36.53） |
| 17 | flags u8 | bit0=本帧数据有效 |
| 18 | crc8 | CRC-8（poly 0x07，init 0x00，覆盖字节 0–17） |
| 19 | rsv | 保留 0x00 |

App 端换算：`g = mg / 1000`，`°/s = centiDps / 100`；pitch/roll 由重力分量在 App 端解算（X24）。

## 验收

1. **nRF Connect**（手机 App）扫描 `FocusDu-IMU` → 连接 → 订阅 `6F0D0002`：
   每 40ms 收到 20 字节 Notify，静置时 az ≈ 1000±50，gx/gy/gz ≈ 0±30；
2. 用 `python decode_imu_frame.py <20字节hex>` 离线核对帧字段与 CRC；
3. App 端 X23 接入后：绑定页可见连接状态，断开 ESP32 电源 → App 侧断流兜底提示（X06 语义）。

## 已知边界

- v0 无电池服务（USB 供电演示场景用不到），v1 如需佩戴再加 Battery Service 0x180F；
- 控制特征 v0 只收不解析；
- 无自动姿态基线：静坐基线采集在 App 端做（X24 / ImuBaseline）。
