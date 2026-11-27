#!/usr/bin/env python3
"""X25 验收工具：解析 FocusDu IMU 的 20 字节 BLE 帧（协议见 README.md）。

用法：
  python decode_imu_frame.py 01000000e803e803e803000000000000190100xx00
  python decode_imu_frame.py --selftest     # 用内置样例帧自检（含 CRC 校验演示）
"""
import sys


def crc8(data: bytes) -> int:
    crc = 0
    for b in data:
        crc ^= b
        for _ in range(8):
            crc = ((crc << 1) ^ 0x07) & 0xFF if crc & 0x80 else (crc << 1) & 0xFF
    return crc


def i16(v: int) -> int:
    return v - 0x10000 if v & 0x8000 else v


def decode(frame: bytes) -> dict:
    if len(frame) != 20:
        raise ValueError(f"帧长 {len(frame)} != 20")
    crc_ok = crc8(frame[:18]) == frame[18]
    return {
        "seq": int.from_bytes(frame[0:4], "little"),
        "ax_mg": i16(int.from_bytes(frame[4:6], "little")),
        "ay_mg": i16(int.from_bytes(frame[6:8], "little")),
        "az_mg": i16(int.from_bytes(frame[8:10], "little")),
        "gx_dps": i16(int.from_bytes(frame[10:12], "little")) / 100.0,
        "gy_dps": i16(int.from_bytes(frame[12:14], "little")) / 100.0,
        "gz_dps": i16(int.from_bytes(frame[14:16], "little")) / 100.0,
        "temp_c": i16(frame[16]) if frame[16] & 0x80 else frame[16],
        "flags": f"0x{frame[17]:02X}",
        "valid": bool(frame[17] & 0x01),
        "crc_ok": crc_ok,
        "rsv": f"0x{frame[19]:02X}",
    }


def main():
    if len(sys.argv) > 1 and sys.argv[1] == "--selftest":
        # 静置样例：seq=1, ax=ay=0mg, az=1000mg, 角速度全 0, temp=25°C, flags=0x01
        f = bytearray(20)
        f[0:4] = (1).to_bytes(4, "little")
        f[8:10] = (1000).to_bytes(2, "little", signed=True)
        f[16] = 25
        f[17] = 0x01
        f[18] = crc8(f[:18])
        print("样例帧:", f.hex().upper())
        print(decode(bytes(f)))
        return

    if len(sys.argv) != 2:
        print(__doc__)
        sys.exit(1)
    print(decode(bytes.fromhex(sys.argv[1].replace(" ", ""))))


if __name__ == "__main__":
    main()
