#!/usr/bin/env python3
import argparse
import csv
import os
import time
from datetime import datetime


def read_rows(path):
    if not path or not os.path.isfile(path):
        return [], []
    with open(path, newline="", encoding="utf-8-sig") as handle:
        reader = csv.DictReader(handle)
        header = list(reader.fieldnames or [])
        rows = [row for row in reader if any((value or "").strip() for value in row.values())]
    return header, rows


def union_header(primary, secondary):
    header = []
    seen = set()
    for source in (primary or []), (secondary or []):
        for col in source:
            key = (col or "").strip().lower()
            if key and key not in seen:
                seen.add(key)
                header.append(col)
    return header


def row_ip(row):
    for key in row.keys():
        if (key or "").strip().lower().replace(" ", "") == "iploopback":
            return (row.get(key) or "").strip()
    return ""


def output_path(output_dir, prefix, stamp):
    path = os.path.join(output_dir, "%s_%s.csv" % (prefix, stamp))
    while os.path.exists(path):
        time.sleep(1)
        stamp = datetime.now().strftime("%Y-%m-%d_%H%M%S")
        path = os.path.join(output_dir, "%s_%s.csv" % (prefix, stamp))
    return path


def write_rows(path, header, rows):
    tmp = path + ".tmp"
    with open(tmp, "w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=header, extrasaction="ignore")
        writer.writeheader()
        for row in rows:
            writer.writerow({col: row.get(col, "") for col in header})
    os.replace(tmp, path)


def merge_one(prefix, baseline_path, incremental_path, output_dir, refreshed_ips, stamp):
    base_header, base_rows = read_rows(baseline_path)
    inc_header, inc_rows = read_rows(incremental_path)
    if not inc_rows:
        print("[MERGE-INCR] %s: no incremental rows, skip." % prefix)
        return ""

    header = union_header(base_header, inc_header)
    if not header:
        print("[MERGE-INCR] %s: no header, skip." % prefix)
        return ""

    kept_rows = []
    removed = 0
    for row in base_rows:
        ip = row_ip(row)
        if ip and ip in refreshed_ips:
            removed += 1
            continue
        kept_rows.append(row)

    merged_rows = kept_rows + inc_rows
    out = output_path(output_dir, prefix, stamp)
    write_rows(out, header, merged_rows)
    print("[MERGE-INCR] %s: baseline=%s incremental=%s refreshedIps=%d removed=%d appended=%d output=%s totalRows=%d"
          % (prefix,
             os.path.basename(baseline_path) if baseline_path else "",
             os.path.basename(incremental_path) if incremental_path else "",
             len(refreshed_ips), removed, len(inc_rows), out, len(merged_rows)))
    return out


def main():
    parser = argparse.ArgumentParser(description="Merge incremental Link Optical CSV output into latest baseline CSVs.")
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--baseline-lldp", default="")
    parser.add_argument("--incremental-lldp", default="")
    parser.add_argument("--baseline-port", default="")
    parser.add_argument("--incremental-port", default="")
    parser.add_argument("--baseline-description", default="")
    parser.add_argument("--incremental-description", default="")
    args = parser.parse_args()

    os.makedirs(args.output_dir, exist_ok=True)

    _, lldp_rows = read_rows(args.incremental_lldp)
    refreshed_ips = {row_ip(row) for row in lldp_rows if row_ip(row)}
    if not refreshed_ips:
        print("[MERGE-INCR] No refreshed IP loopback found in incremental LLDP; keep existing MapViewer input.")
        return 0

    stamp = datetime.now().strftime("%Y-%m-%d_%H%M%S")
    merge_one("DataLLDP_Neighbor", args.baseline_lldp, args.incremental_lldp, args.output_dir, refreshed_ips, stamp)
    merge_one("DataPort", args.baseline_port, args.incremental_port, args.output_dir, refreshed_ips, stamp)
    merge_one("DataDescription_MB", args.baseline_description, args.incremental_description, args.output_dir, refreshed_ips, stamp)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
