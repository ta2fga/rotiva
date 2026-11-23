"""BLE tarama aracı.
    python tools/ble_scan.py                 # yakındaki cihazları listeler
    python tools/ble_scan.py --services AA:BB:CC:DD:EE:FF   # servis/karakteristikleri yazdırır
"""
import argparse
import asyncio
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from app.ble import scan, is_obd_name, pick_characteristics  # noqa: E402


async def show(addr):
    from bleak import BleakClient
    async with BleakClient(addr, timeout=15) as c:
        for s in c.services:
            print(f"Servis {s.uuid}")
            for ch in s.characteristics:
                print(f"   {ch.uuid}  {','.join(ch.properties)}")
        try:
            n, w = pick_characteristics(c.services)
            print(f"\nSeçilecek: notify={n.uuid} write={w.uuid}")
        except Exception as e:
            print("Otomatik seçim başarısız:", e)


ap = argparse.ArgumentParser()
ap.add_argument("--services", metavar="ADRES")
ap.add_argument("--timeout", type=float, default=6.0)
a = ap.parse_args()
if a.services:
    asyncio.run(show(a.services))
else:
    print(f"{a.timeout:.0f} sn taranıyor...")
    for name, addr, rssi in scan(a.timeout):
        print(f"{'*' if is_obd_name(name) else ' '} {name:<28} {addr}  {rssi} dBm")
