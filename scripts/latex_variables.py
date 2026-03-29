"""
Read-modify-write utility for the shared LaTeX variables CSV.

Format: name,value,description (with header row).
Both Python scripts and Java pipelines use the same file so that a single
CSV can be loaded by the LaTeX ``datatool`` package.
"""

import csv
import os
import tempfile
from collections import OrderedDict
from pathlib import Path

DEFAULT_CSV = Path.home() / "data" / "bruteforcemerge" / "common" / "latex_variables.csv"


def put(name: str, value, description: str = "", csv_path: Path = DEFAULT_CSV):
    """Upsert a single variable."""
    put_all({name: (str(value), description)}, csv_path)


def put_all(entries: dict, csv_path: Path = DEFAULT_CSV):
    """
    Batch upsert.  *entries* maps name → (value, description).
    Value-only entries (name → value) are also accepted.
    """
    normalized = {}
    for name, val in entries.items():
        if isinstance(val, (list, tuple)):
            normalized[name] = (str(val[0]), str(val[1]) if len(val) > 1 else "")
        else:
            normalized[name] = (str(val), "")

    existing = _read_csv(csv_path)
    existing.update(normalized)
    _write_csv(csv_path, existing)


def _read_csv(csv_path: Path) -> OrderedDict:
    data = OrderedDict()
    if not csv_path.exists():
        return data
    with open(csv_path, newline="") as f:
        reader = csv.DictReader(f)
        for row in reader:
            data[row["name"]] = (row.get("value", ""), row.get("description", ""))
    return data


def _write_csv(csv_path: Path, data: OrderedDict):
    csv_path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=csv_path.parent, suffix=".tmp")
    try:
        with os.fdopen(fd, "w", newline="") as f:
            writer = csv.writer(f)
            writer.writerow(["name", "value", "description"])
            for name, (value, desc) in data.items():
                writer.writerow([name, value, desc])
        os.replace(tmp, csv_path)
    except BaseException:
        os.unlink(tmp)
        raise
