#!/usr/bin/env python3
"""Remove TRUE Link Optical logs for selected enabled workbook node types.

Log filenames contain a workbook row number, but row numbers are not stable when
UserInterface_Input.xlsx is compacted.  Match the current node identity first and
use the row number only as a compatibility fallback for filenames that cannot be
parsed.
"""

import argparse
import collections
import re
import sys
import zipfile
from pathlib import Path
from xml.etree import ElementTree as ET


MAIN_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
DOC_REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
PKG_REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
CELL_REF_RE = re.compile(r"^([A-Z]+)")
LOG_ROW_RE = re.compile(r"^\[(\d+)\]")
LOG_IDENTITY_RE = re.compile(
    r"^\[(?P<row>\d+)\](?P<ip>\d{1,3}(?:\.\d{1,3}){3})_"
    r"(?P<device>.+?)_(?:HW|N|ZTE)-LLDP-Link_OPTIC_"
    r"(?P<date>\d{4}-\d{2}-\d{2})\.txt$",
    re.IGNORECASE,
)
NODE_TYPE_RE = re.compile(r"^(PN|DN|AN)\d*[-_]", re.IGNORECASE)


def qname(local_name):
    return "{{{}}}{}".format(MAIN_NS, local_name)


def normalize_header(value):
    return re.sub(r"[^a-z0-9]", "", (value or "").strip().lower())


def canonical_device_name(value):
    """Make workbook underscore and filename hyphen forms comparable."""
    return re.sub(r"[^A-Z0-9]", "", (value or "").strip().upper())


def normalize_ip(value):
    return (value or "").strip()


def cell_column(cell_ref):
    match = CELL_REF_RE.match((cell_ref or "").upper())
    return match.group(1) if match else ""


def read_shared_strings(archive):
    try:
        with archive.open("xl/sharedStrings.xml") as handle:
            root = ET.parse(handle).getroot()
    except KeyError:
        return []

    values = []
    for item in root.findall(qname("si")):
        values.append("".join(node.text or "" for node in item.iter(qname("t"))))
    return values


def resolve_sheet_path(archive, sheet_name):
    with archive.open("xl/workbook.xml") as handle:
        workbook = ET.parse(handle).getroot()
    relationship_id = ""
    for sheet in workbook.findall(".//" + qname("sheet")):
        if sheet.attrib.get("name") == sheet_name:
            relationship_id = sheet.attrib.get("{{{}}}id".format(DOC_REL_NS), "")
            break
    if not relationship_id:
        raise RuntimeError("Sheet {!r} was not found".format(sheet_name))

    with archive.open("xl/_rels/workbook.xml.rels") as handle:
        relationships = ET.parse(handle).getroot()
    target = ""
    for relationship in relationships.findall("{{{}}}Relationship".format(PKG_REL_NS)):
        if relationship.attrib.get("Id") == relationship_id:
            target = relationship.attrib.get("Target", "")
            break
    if not target:
        raise RuntimeError("Worksheet relationship {} was not found".format(relationship_id))
    if target.startswith("/"):
        return target.lstrip("/")
    return str(Path("xl") / target).replace("\\", "/")


def read_cell_value(cell, shared_strings):
    cell_type = cell.attrib.get("t", "")
    if cell_type == "inlineStr":
        return "".join(node.text or "" for node in cell.iter(qname("t")))
    value_node = cell.find(qname("v"))
    if value_node is None or value_node.text is None:
        return ""
    raw_value = value_node.text
    if cell_type == "s":
        try:
            return shared_strings[int(raw_value)]
        except (ValueError, IndexError):
            return ""
    return raw_value


def add_identity(identity_map, key, node_type):
    if key:
        identity_map.setdefault(key, set()).add(node_type)


def add_device_identity(identity_map, key, node_type, ip):
    if key:
        identity_map.setdefault(key, set()).add((node_type, ip))


def selected_workbook_nodes(workbook_path, sheet_name, requested_types):
    selected_rows = {}
    selected_devices = {}
    selected_ips = {}
    selected_counts = collections.Counter()
    with zipfile.ZipFile(str(workbook_path), "r") as archive:
        shared_strings = read_shared_strings(archive)
        sheet_path = resolve_sheet_path(archive, sheet_name)
        with archive.open(sheet_path) as handle:
            headers = {}
            run_column = ""
            device_column = ""
            ip_column = ""
            for _, row in ET.iterparse(handle, events=("end",)):
                if row.tag != qname("row"):
                    continue
                row_number = int(row.attrib.get("r", "0") or "0")
                values = {}
                for cell in row.findall(qname("c")):
                    column = cell_column(cell.attrib.get("r", ""))
                    if column:
                        values[column] = read_cell_value(cell, shared_strings).strip()

                if not headers:
                    headers = {
                        normalize_header(value): column
                        for column, value in values.items()
                        if normalize_header(value)
                    }
                    run_column = headers.get("run", "")
                    device_column = headers.get("devicename", "")
                    ip_column = headers.get("loopbackip", "")
                    if not run_column or not device_column:
                        raise RuntimeError(
                            "Required Run and Device-Name columns were not found in {}".format(sheet_name)
                        )
                    row.clear()
                    continue

                if values.get(run_column, "").strip().upper() != "Y":
                    row.clear()
                    continue
                device_name = values.get(device_column, "").strip().upper()
                match = NODE_TYPE_RE.match(device_name)
                if match:
                    node_type = match.group(1).upper()
                    if node_type in requested_types:
                        canonical = canonical_device_name(device_name)
                        ip = normalize_ip(values.get(ip_column, "")) if ip_column else ""
                        selected_rows[row_number] = (node_type, canonical, ip)
                        add_device_identity(selected_devices, canonical, node_type, ip)
                        add_identity(selected_ips, ip, node_type)
                        selected_counts[node_type] += 1
                row.clear()
    return selected_rows, selected_devices, selected_ips, selected_counts


def parse_log_identity(path):
    match = LOG_IDENTITY_RE.match(path.name)
    if not match:
        return None
    return {
        "row": int(match.group("row")),
        "ip": normalize_ip(match.group("ip")),
        "device": canonical_device_name(match.group("device")),
        "date": match.group("date"),
    }


def choose_requested_type(types, requested_types):
    for node_type in requested_types:
        if node_type in types:
            return node_type
    return ""


def match_log_type(path, selected_rows, selected_devices, selected_ips, requested_types):
    identity = parse_log_identity(path)
    if identity is not None:
        device_records = selected_devices.get(identity["device"], set())
        node_type = choose_requested_type(
            {record[0] for record in device_records}, requested_types
        )
        if node_type:
            identity["device_record_count"] = len(device_records)
            identity["device_current_ips"] = {record[1] for record in device_records if record[1]}
            return node_type, "device", identity

        node_type = choose_requested_type(
            selected_ips.get(identity["ip"], set()), requested_types
        )
        if node_type:
            return node_type, "ip", identity

        # A parseable stale filename must not be deleted merely because its old row
        # number has since been assigned to a different workbook device.
        return "", "", identity

    row_match = LOG_ROW_RE.match(path.name)
    if row_match:
        selected = selected_rows.get(int(row_match.group(1)))
        if selected:
            return selected[0], "row-fallback", None
    return "", "", identity


def format_counts(counts, requested_types):
    return " ".join("{}={}".format(node_type, counts.get(node_type, 0)) for node_type in requested_types)


def newest_sort_key(item):
    path, _, _, identity = item
    filename_date = identity.get("date", "") if identity else ""
    return filename_date, path.stat().st_mtime_ns, path.name


def identity_group_key(item):
    path, _, _, identity = item
    if identity:
        if identity.get("device"):
            if identity.get("device_record_count", 1) > 1:
                current_ips = identity.get("device_current_ips", set())
                if identity.get("ip") in current_ips:
                    return "device:{}|ip:{}".format(identity["device"], identity["ip"])
                # Multiple current rows use this Device-Name and the stale IP no
                # longer identifies which row it belonged to. Keep it for manual
                # review instead of risking deletion of a legitimate node.
                return "ambiguous:" + path.name
            return "device:" + identity["device"]
        if identity.get("ip"):
            return "ip:" + identity["ip"]
    return "path:" + path.name


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--workbook", required=True)
    parser.add_argument("--total-log-dir", required=True)
    parser.add_argument("--types", default="PN,DN,AN")
    parser.add_argument("--sheet", default="deviceList_TRUE")
    parser.add_argument(
        "--keep-newest",
        action="store_true",
        help="Remove only older duplicate snapshots for each matched device",
    )
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()

    workbook_path = Path(args.workbook).expanduser().resolve()
    total_log_dir = Path(args.total_log_dir).expanduser().resolve()
    requested_types = []
    for raw_type in args.types.split(","):
        node_type = raw_type.strip().upper()
        if node_type and node_type not in requested_types:
            requested_types.append(node_type)
    unsupported_types = [node_type for node_type in requested_types if node_type not in {"PN", "DN", "AN"}]

    if not workbook_path.is_file():
        parser.error("Workbook not found: {}".format(workbook_path))
    if not total_log_dir.is_dir():
        parser.error("Total_Log directory not found: {}".format(total_log_dir))
    if not requested_types:
        parser.error("At least one node type is required")
    if unsupported_types:
        parser.error("Unsupported node type(s): {}".format(",".join(unsupported_types)))

    selected_rows, selected_devices, selected_ips, selected_counts = selected_workbook_nodes(
        workbook_path,
        args.sheet,
        requested_types,
    )
    if not selected_rows:
        print("[ERROR] No enabled workbook rows matched types={}".format(",".join(requested_types)), file=sys.stderr)
        return 2

    matching_files = []
    file_counts = collections.Counter()
    method_counts = collections.Counter()
    for path in total_log_dir.glob("*.txt"):
        node_type, method, identity = match_log_type(
            path, selected_rows, selected_devices, selected_ips, requested_types
        )
        if node_type:
            item = (path, node_type, method, identity)
            matching_files.append(item)
            file_counts[node_type] += 1
            method_counts[method] += 1

    print(
        "[TYPE-CLEAN] Enabled workbook rows: {} total={}".format(
            format_counts(selected_counts, requested_types), len(selected_rows)
        )
    )
    print(
        "[TYPE-CLEAN] Matching Total_Log files: {} total={}".format(
            format_counts(file_counts, requested_types), len(matching_files)
        )
    )
    print(
        "[TYPE-CLEAN] Match methods: device={} ip={} rowFallback={}".format(
            method_counts.get("device", 0),
            method_counts.get("ip", 0),
            method_counts.get("row-fallback", 0),
        )
    )

    files_to_remove = matching_files
    if args.keep_newest:
        grouped = collections.defaultdict(list)
        for item in matching_files:
            grouped[identity_group_key(item)].append(item)
        files_to_remove = []
        for items in grouped.values():
            ordered = sorted(items, key=newest_sort_key)
            files_to_remove.extend(ordered[:-1])
        stale_counts = collections.Counter(item[1] for item in files_to_remove)
        print(
            "[TYPE-CLEAN] Stale duplicate files: {} total={}; newest snapshot per device is kept.".format(
                format_counts(stale_counts, requested_types), len(files_to_remove)
            )
        )

    if not args.apply:
        print("[DRY-RUN] No Total_Log files were removed.")
        return 0

    removed_counts = collections.Counter()
    for path, node_type, _, _ in files_to_remove:
        path.unlink()
        removed_counts[node_type] += 1
    action = "stale duplicate" if args.keep_newest else "selected"
    print(
        "[CLEAN] Removed {} Total_Log files: {} total={}".format(
            action, format_counts(removed_counts, requested_types), len(files_to_remove)
        )
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
