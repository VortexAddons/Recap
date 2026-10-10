#!/usr/bin/env python3
"""Build the firmware with PlatformIO and stage it for the web flasher.

Usage (from the firmware/ folder):   python release.py

Creates ../flasher/firmware/{bootloader,partitions,boot_app0,firmware}.bin + manifest.json
"""
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path

root = Path(__file__).resolve().parent
out = root.parent / "flasher" / "firmware"
build = root / ".pio" / "build" / "esp32dev"
boot_app0 = (Path.home() / ".platformio" / "packages" / "framework-arduinoespressif32"
             / "tools" / "partitions" / "boot_app0.bin")


def find_pio():
    for name in ("pio", "platformio"):
        p = shutil.which(name)
        if p:
            return [p]
    penv = Path.home() / ".platformio" / "penv"
    for c in (penv / "Scripts" / "pio.exe", penv / "bin" / "pio"):
        if c.exists():
            return [str(c)]
    return [sys.executable, "-m", "platformio"]


print("Building firmware...")
subprocess.run(find_pio() + ["run"], cwd=root, check=True)

version = re.search(r'#define FW_VERSION "([^"]+)"', (root / "src" / "main.cpp").read_text()).group(1)

out.mkdir(parents=True, exist_ok=True)
shutil.copy(build / "bootloader.bin", out / "bootloader.bin")
shutil.copy(build / "partitions.bin", out / "partitions.bin")
shutil.copy(build / "firmware.bin", out / "firmware.bin")
shutil.copy(boot_app0, out / "boot_app0.bin")

manifest = {
    "name": "Recapper",
    "version": version,
    "new_install_prompt_erase": True,
    "builds": [{
        "chipFamily": "ESP32",
        "parts": [
            {"path": "bootloader.bin", "offset": 0x1000},
            {"path": "partitions.bin", "offset": 0x8000},
            {"path": "boot_app0.bin", "offset": 0xE000},
            {"path": "firmware.bin", "offset": 0x10000},
        ],
    }],
}
(out / "manifest.json").write_text(json.dumps(manifest, indent=2))
print(f"Done. Firmware {version} staged in {out}")
