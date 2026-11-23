# Data sources

| Source | Used for | License |
|---|---|---|
| https://github.com/ludwig-v/arduino-psa-diag (ECU_LIST.md, dtc/EDC17C10.md, README.md) | ECU tx:rx CAN IDs, session commands (KWP 10C0 / UDS 1003), EDC17C10 DTC list (translated to Turkish) | GPL-3.0. Copyleft: derived data/code must be GPL-compatible; check before commercial or closed-source distribution. |
| https://github.com/prototux/PSA-RE | Reviewed only (passive CAN frame definitions; no diagnostic DIDs used) | Apache-2.0 |
| https://blue-van.blogspot.com/2020/12/finding-peugeot-expert-extended-pids.html | Engine speed 21 C9 80 01 (low confidence) | No license stated; all rights reserved by the author |
| SAE J1979 | Standard OBD-II PIDs | Public standard |

Raw downloads (data only) are in the session scratchpad dl/ folder.
