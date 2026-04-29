#!/usr/bin/env python3
import csv
import json
import os
import re
import sys
import urllib.error
import urllib.request
from collections import Counter, defaultdict
from datetime import datetime
from pathlib import Path

MARKER = "<!-- botgetlog-analytics -->"
PAYLOAD_BLOCK = re.compile(r"```json\s*(\{.*?\})\s*```", re.DOTALL)
API_VERSION = "2022-11-28"


def env(name: str) -> str:
    return os.environ.get(name, "").strip()


def github_get(url: str, token: str):
    request = urllib.request.Request(url)
    request.add_header("Accept", "application/vnd.github+json")
    request.add_header("Authorization", f"Bearer {token}")
    request.add_header("X-GitHub-Api-Version", API_VERSION)
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.loads(response.read().decode("utf-8"))


def list_issue_comments(repo: str, issue_number: str, token: str):
    comments = []
    page = 1
    while True:
        url = (
            f"https://api.github.com/repos/{repo}/issues/{issue_number}/comments"
            f"?per_page=100&page={page}"
        )
        page_items = github_get(url, token)
        if not page_items:
            break
        comments.extend(page_items)
        if len(page_items) < 100:
            break
        page += 1
    return comments


def parse_payload(comment):
    body = comment.get("body", "")
    if MARKER not in body:
        return None
    match = PAYLOAD_BLOCK.search(body)
    if not match:
        return None
    try:
        payload = json.loads(match.group(1))
        if not isinstance(payload, dict):
            return None
        return payload
    except json.JSONDecodeError:
        return None


def safe_iso_to_date(value: str, fallback: str):
    text = (value or "").strip()
    if not text:
        return fallback, fallback[:7] if len(fallback) >= 7 else ""
    try:
        normalized = text.replace("Z", "+00:00")
        dt = datetime.fromisoformat(normalized)
        event_date = dt.date().isoformat()
        return event_date, event_date[:7]
    except ValueError:
        if len(text) >= 10:
            event_date = text[:10]
            return event_date, event_date[:7]
        return fallback, fallback[:7] if len(fallback) >= 7 else ""


def build_rows(comments):
    rows = []
    seen_ids = set()
    for comment in comments:
        payload = parse_payload(comment)
        if not payload:
            continue
        received_at = (comment.get("created_at") or "").strip()
        source = (payload.get("source") or "").strip()
        batch_created_at = (payload.get("batch_created_at") or "").strip()
        events = payload.get("events") or []
        if not isinstance(events, list):
            continue
        fallback_date, _ = safe_iso_to_date(received_at, "")
        for event in events:
            if not isinstance(event, dict):
                continue
            event_id = str(event.get("event_id") or "").strip()
            if not event_id:
                event_id = f"{comment.get('id', '0')}-{len(rows)}"
            if event_id in seen_ids:
                continue
            seen_ids.add(event_id)
            started_at = str(event.get("started_at") or "").strip()
            event_date, event_month = safe_iso_to_date(started_at or received_at, fallback_date)
            rows.append(
                {
                    "event_id": event_id,
                    "event_type": str(event.get("event_type") or "").strip(),
                    "tool_name": str(event.get("tool_name") or source).strip(),
                    "app_version": str(event.get("app_version") or "").strip(),
                    "machine_id": str(event.get("machine_id") or "").strip(),
                    "started_at": started_at,
                    "queued_at": str(event.get("queued_at") or "").strip(),
                    "received_at": received_at,
                    "batch_created_at": batch_created_at,
                    "event_date": event_date,
                    "event_month": event_month,
                }
            )
    rows.sort(key=lambda row: (row["started_at"], row["received_at"], row["event_id"]))
    return rows


def write_csv(path: Path, fieldnames, rows):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        for row in rows:
            writer.writerow(row)


def build_daily_summary(rows):
    launches = Counter()
    unique = defaultdict(set)
    for row in rows:
        event_date = row["event_date"]
        if not event_date:
            continue
        launches[event_date] += 1
        machine_id = row["machine_id"]
        if machine_id:
            unique[event_date].add(machine_id)
    summary = []
    for event_date in sorted(set(list(launches.keys()) + list(unique.keys()))):
        summary.append(
            {
                "event_date": event_date,
                "launches": launches[event_date],
                "unique_users": len(unique[event_date]),
            }
        )
    return summary


def build_monthly_summary(rows):
    launches = Counter()
    unique = defaultdict(set)
    for row in rows:
        event_month = row["event_month"]
        if not event_month:
            continue
        launches[event_month] += 1
        machine_id = row["machine_id"]
        if machine_id:
            unique[event_month].add(machine_id)
    summary = []
    for event_month in sorted(set(list(launches.keys()) + list(unique.keys()))):
        summary.append(
            {
                "event_month": event_month,
                "launches": launches[event_month],
                "unique_users": len(unique[event_month]),
            }
        )
    return summary


def build_version_summary(rows):
    unique = defaultdict(set)
    for row in rows:
        version = row["app_version"]
        machine_id = row["machine_id"]
        if version and machine_id:
            unique[version].add(machine_id)
    summary = []
    for version in sorted(unique.keys()):
        summary.append(
            {
                "app_version": version,
                "unique_users": len(unique[version]),
            }
        )
    return summary


def write_readme(path: Path, daily_summary, monthly_summary, version_summary):
    today = daily_summary[-1] if daily_summary else None
    current_month = monthly_summary[-1] if monthly_summary else None
    lines = [
        "# Usage Analytics",
        "",
        "This folder is generated from batched launch events stored in the analytics issue thread.",
        "",
    ]
    if today:
        lines.extend(
            [
                "## Latest Daily Snapshot",
                "",
                f"- Date: `{today['event_date']}`",
                f"- Launches: `{today['launches']}`",
                f"- Unique users: `{today['unique_users']}`",
                "",
            ]
        )
    if current_month:
        lines.extend(
            [
                "## Latest Monthly Snapshot",
                "",
                f"- Month: `{current_month['event_month']}`",
                f"- Launches: `{current_month['launches']}`",
                f"- Unique users: `{current_month['unique_users']}`",
                "",
            ]
        )
    if version_summary:
        lines.extend(["## Versions", ""])
        for item in version_summary:
            lines.append(f"- `{item['app_version']}`: `{item['unique_users']}` unique users")
        lines.append("")
    path.write_text("\n".join(lines), encoding="utf-8")


def main():
    token = env("GITHUB_TOKEN")
    repo = env("GITHUB_REPOSITORY")
    issue_number = env("ANALYTICS_ISSUE_NUMBER")
    output_dir = Path(env("ANALYTICS_OUTPUT_DIR") or "analytics")

    if not token:
        raise SystemExit("GITHUB_TOKEN is required.")
    if not repo:
        raise SystemExit("GITHUB_REPOSITORY is required.")
    if not issue_number:
        raise SystemExit("ANALYTICS_ISSUE_NUMBER is required.")

    comments = list_issue_comments(repo, issue_number, token)
    rows = build_rows(comments)

    write_csv(
        output_dir / "events.csv",
        [
            "event_id",
            "event_type",
            "tool_name",
            "app_version",
            "machine_id",
            "started_at",
            "queued_at",
            "received_at",
            "batch_created_at",
            "event_date",
            "event_month",
        ],
        rows,
    )

    daily_summary = build_daily_summary(rows)
    monthly_summary = build_monthly_summary(rows)
    version_summary = build_version_summary(rows)

    write_csv(output_dir / "daily_summary.csv", ["event_date", "launches", "unique_users"], daily_summary)
    write_csv(output_dir / "monthly_summary.csv", ["event_month", "launches", "unique_users"], monthly_summary)
    write_csv(output_dir / "version_summary.csv", ["app_version", "unique_users"], version_summary)
    write_readme(output_dir / "README.md", daily_summary, monthly_summary, version_summary)

    print(f"Processed {len(rows)} analytics events from issue #{issue_number}.")


if __name__ == "__main__":
    try:
        main()
    except urllib.error.HTTPError as error:
        message = error.read().decode("utf-8", errors="replace")
        print(message, file=sys.stderr)
        raise
