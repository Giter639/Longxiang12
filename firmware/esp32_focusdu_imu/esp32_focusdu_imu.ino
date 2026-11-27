/*
 * FocusDu IMU 固件（X25）—— ESP32 + MPU6050，BLE GATT Notify 上报原始 6 轴
 *
 * 协议对齐 X22（FocusDu/app/src/main/java/com/focusdu/app/imu/ImuModels.kt）：
 *   - 服务 UUID 6F0D0001-…，数据特征值 Notify 20 字节定长帧，25Hz；
 *   - 默认 MTU 23 恰好容纳（20 载荷 + 3 ATT 头），无需协商；
 *   - 姿态角/运动强度等特征由 App 端 X23/X24 计算并判定五态，固件只管采和传；
 *   - 任何协议改动必须固件与 App 两端同步（帧格式见同目录 README.md）。
 *
 * 接线（常见 ESP32 Dev Module / ESP32-WROOM 板）：
 *   MPU6050 VCC→3V3  GND→GND  SDA→GPIO21  SCL→GPIO22  AD0→GND(地址0x68)
 *
 * 烧录与验收步骤见同目录 README.md；帧字节可用 decode_imu_frame.py 离线核对。
 */

#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <Wire.h>

// ---- 协议常量（与 App 端 X23 共用，两端同步改）----
static const char *SERVICE_UUID   = "6f0d0001-5a2e-4c1e-9b3a-46a5d2f10e01";
static const char *DATA_CHAR_UUID = "6f0d0002-5a2e-4c1e-9b3a-46a5d2f10e01"; // Notify：20 字节帧
static const char *CTRL_CHAR_UUID = "6f0d0003-5a2e-4c1e-9b3a-46a5d2f10e01"; // Write：v0 保留

static const uint32_t SAMPLE_PERIOD_MS = 40;   // 25Hz：姿态检测足够，且省电省空口

// ---- MPU6050 寄存器 ----
static const uint8_t MPU_ADDR          = 0x68; // AD0 接 GND
static const uint8_t REG_PWR_MGMT_1    = 0x6B;
static const uint8_t REG_SMPLRT_DIV    = 0x19;
static const uint8_t REG_CONFIG        = 0x1A; // DLPF_CFG=3 → 44Hz 带宽，滤掉高频抖动
static const uint8_t REG_GYRO_CONFIG   = 0x1B; // ±250°/s
static const uint8_t REG_ACCEL_CONFIG  = 0x1C; // ±2g
static const uint8_t REG_ACCEL_XOUT_H  = 0x3B; // 14 字节连续读：6轴+温度
static const uint8_t REG_WHO_AM_I      = 0x75;

static const float ACC_LSB_PER_G   = 16384.0f; // ±2g 量程
static const float GYR_LSB_PER_DPS = 131.0f;   // ±250°/s 量程

#ifndef LED_BUILTIN
#define LED_BUILTIN 2   // 常见 ESP32 Dev Module 板载灯
#endif

BLEServer *gServer = nullptr;
BLECharacteristic *gDataChar = nullptr;
volatile bool gClientConnected = false;

uint32_t gSeq = 0;
uint32_t gLastSampleMs = 0;
uint32_t gReadFailCount = 0;
uint8_t gFrame[20];   // seq u32 | ax,ay,az i16(mg) | gx,gy,gz i16(0.01°/s) | temp i8 | flags u8 | crc8 u8 | rsv u8

static void writeReg(uint8_t reg, uint8_t val) {
  Wire.beginTransmission(MPU_ADDR);
  Wire.write(reg);
  Wire.write(val);
  Wire.endTransmission();
}

static void readRegBytes(uint8_t reg, uint8_t *buf, size_t len) {
  Wire.beginTransmission(MPU_ADDR);
  Wire.write(reg);
  Wire.endTransmission(false);   // repeated start：读前不发 STOP
  Wire.requestFrom((int)MPU_ADDR, (int)len);
  for (size_t i = 0; i < len && Wire.available(); i++) buf[i] = Wire.read();
}

static uint8_t crc8(const uint8_t *data, size_t len) {
  uint8_t crc = 0x00;
  for (size_t i = 0; i < len; i++) {
    crc ^= data[i];
    for (uint8_t b = 0; b < 8; b++) {
      crc = (crc & 0x80) ? (uint8_t)((crc << 1) ^ 0x07) : (uint8_t)(crc << 1);
    }
  }
  return crc;
}

static int16_t i16le(const uint8_t *p) {
  return (int16_t)((uint16_t)p[0] | ((uint16_t)p[1] << 8));
}

static void put16le(uint8_t *p, int16_t v) {
  p[0] = (uint8_t)(v & 0xFF);
  p[1] = (uint8_t)((v >> 8) & 0xFF);
}

static void put32le(uint8_t *p, uint32_t v) {
  p[0] = (uint8_t)(v & 0xFF);
  p[1] = (uint8_t)((v >> 8) & 0xFF);
  p[2] = (uint8_t)((v >> 16) & 0xFF);
  p[3] = (uint8_t)((v >> 24) & 0xFF);
}

/** 读 6 轴并按 X22 协议单位换算打包：加速度毫g、角速度 0.01°/s */
static bool sampleAndPack() {
  uint8_t raw[14];
  readRegBytes(REG_ACCEL_XOUT_H, raw, sizeof(raw));
  if (Wire.lastError() != I2C_ERROR_OK && raw[0] == 0 && raw[1] == 0) {
    return false;   // 粗判：读取失败交给上层计数告警（全零帧不伪装成有效数据）
  }

  float axG = i16le(raw + 0) / ACC_LSB_PER_G;
  float ayG = i16le(raw + 2) / ACC_LSB_PER_G;
  float azG = i16le(raw + 4) / ACC_LSB_PER_G;
  float gxDps = i16le(raw + 8) / GYR_LSB_PER_DPS;
  float gyDps = i16le(raw + 10) / GYR_LSB_PER_DPS;
  float gzDps = i16le(raw + 12) / GYR_LSB_PER_DPS;
  float tempC = i16le(raw + 6) / 340.0f + 36.53f;

  put32le(gFrame, gSeq);
  put16le(gFrame + 4, (int16_t)(axG * 1000.0f));        // 毫g，±2000 在 i16 内
  put16le(gFrame + 6, (int16_t)(ayG * 1000.0f));
  put16le(gFrame + 8, (int16_t)(azG * 1000.0f));
  put16le(gFrame + 10, (int16_t)(gxDps * 100.0f));      // 0.01°/s，±25000 在 i16 内
  put16le(gFrame + 12, (int16_t)(gyDps * 100.0f));
  put16le(gFrame + 14, (int16_t)(gzDps * 100.0f));
  gFrame[16] = (uint8_t)constrain((int)tempC, -128, 127);
  gFrame[17] = 0x01;                                     // bit0：本帧数据有效
  gFrame[18] = crc8(gFrame, 18);
  gFrame[19] = 0x00;                                     // 保留
  return true;
}

class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *) override {
    gClientConnected = true;
    digitalWrite(LED_BUILTIN, HIGH);
    Serial.println("BLE client connected");
  }
  void onDisconnect(BLEServer *) override {
    gClientConnected = false;
    digitalWrite(LED_BUILTIN, LOW);
    gServer->getAdvertising()->start();   // 断连自动回广告，App 重连即恢复
    Serial.println("BLE client disconnected; advertising again");
  }
};

class CtrlCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *c) override {
    // v0 保留：采样率/量程配置 X25 v1 再开放，先不解析避免误触发
    Serial.println("ctrl characteristic written (ignored in v0)");
  }
};

void setup() {
  Serial.begin(115200);
  pinMode(LED_BUILTIN, OUTPUT);
  digitalWrite(LED_BUILTIN, LOW);

  Wire.begin(21, 22);   // SDA, SCL
  Wire.setClock(400000);

  writeReg(REG_PWR_MGMT_1, 0x00);   // 退出睡眠，内部 8MHz 时钟（PLL 锁定前先唤醒）
  delay(50);
  writeReg(REG_PWR_MGMT_1, 0x01);   // PLL X gyro 作时钟基准，抖动更小
  writeReg(REG_SMPLRT_DIV, 0x01);   // 1kHz/(1+1)=500Hz 内部采样
  writeReg(REG_CONFIG, 0x03);       // DLPF 44Hz
  writeReg(REG_GYRO_CONFIG, 0x00);  // ±250°/s
  writeReg(REG_ACCEL_CONFIG, 0x00); // ±2g

  uint8_t who = 0;
  readRegBytes(REG_WHO_AM_I, &who, 1);
  if (who != 0x68) {
    // 克隆芯片 WHO_AM_I 可能不同：只告警不阻塞，现场排查优先看这里
    Serial.printf("WARN: MPU WHO_AM_I=0x%02X (expect 0x68), check wiring/AD0\n", who);
  }

  BLEDevice::init("FocusDu-IMU");
  gServer = BLEDevice::createServer();
  gServer->setCallbacks(new ServerCallbacks());

  BLEService *svc = gServer->createService(SERVICE_UUID);
  gDataChar = svc->createCharacteristic(
      DATA_CHAR_UUID, BLECharacteristic::PROPERTY_NOTIFY);
  gDataChar->addDescriptor(new BLE2902());   // Client Characteristic Config：订阅开关

  BLECharacteristic *ctrl = svc->createCharacteristic(
      CTRL_CHAR_UUID, BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_WRITE);
  ctrl->setCallbacks(new CtrlCallbacks());

  svc->start();

  BLEAdvertising *adv = gServer->getAdvertising();
  adv->addServiceUUID(SERVICE_UUID);   // App 端按服务 UUID 过滤扫描（X23）
  adv->setScanResponse(true);
  adv->start();
  Serial.println("FocusDu-IMU advertising, 25Hz frames on DATA char");
}

void loop() {
  uint32_t now = millis();
  if (gClientConnected && now - gLastSampleMs >= SAMPLE_PERIOD_MS) {
    gLastSampleMs = now;
    if (sampleAndPack()) {
      gDataChar->setValue(gFrame, sizeof(gFrame));
      gDataChar->notify();
      gSeq++;
    } else {
      gReadFailCount++;
      if (gReadFailCount % 250 == 1) {   // 25Hz 下约每 10 秒报一次，避免刷屏
        Serial.printf("MPU read failed x%u, check I2C wiring\n", gReadFailCount);
      }
    }
  }
  delay(2);
}
