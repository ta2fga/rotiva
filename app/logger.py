"""CSV veri kaydı."""
from __future__ import annotations

import csv
import time
from datetime import datetime


class DataLogger:
    def __init__(self):
        self.columns: list = []     # (key, başlık)
        self.rows: list = []
        self.t0 = 0.0
        self.running = False

    def start(self, columns: list):
        self.columns, self.rows, self.t0, self.running = list(columns), [], time.time(), True

    def stop(self):
        self.running = False

    def sample(self, values: dict):
        if not self.running:
            return
        self.rows.append([datetime.now().strftime("%H:%M:%S.%f")[:-3], round(time.time() - self.t0, 2)] +
                         [values.get(k) for k, _ in self.columns])

    def header(self) -> list:
        return ["Saat", "Süre (sn)"] + [h for _, h in self.columns]

    def export_csv(self, path: str):
        with open(path, "w", newline="", encoding="utf-8-sig") as f:   # Excel için BOM
            w = csv.writer(f, delimiter=";")
            w.writerow(self.header())
            for r in self.rows:
                w.writerow(["" if v is None else (str(v).replace(".", ",") if isinstance(v, float) else v)
                            for v in r])
