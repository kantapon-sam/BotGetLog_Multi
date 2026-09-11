import subprocess
import datetime
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "clean_true_linkoptical_logs_by_type.py"


def inline_cell(ref, value):
    return '<c r="{}" t="inlineStr"><is><t>{}</t></is></c>'.format(ref, value)


def make_workbook(path):
    sheet = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>
<row r="1">{}</row>
<row r="2">{}</row>
<row r="3">{}</row>
</sheetData></worksheet>""".format(
        "".join([
            inline_cell("A1", "Run"),
            inline_cell("B1", "Group"),
            inline_cell("C1", "Device-Name"),
            inline_cell("D1", "Loopback-IP"),
        ]),
        "".join([
            inline_cell("A2", "Y"),
            inline_cell("B2", "AN"),
            inline_cell("C2", "AN-SPK08-2_SMP01003S01"),
            inline_cell("D2", "10.85.159.146"),
        ]),
        "".join([
            inline_cell("A3", "Y"),
            inline_cell("B3", "CPE"),
            inline_cell("C3", "CPE-KEEP0001"),
            inline_cell("D3", "10.167.1.1"),
        ]),
    )
    workbook = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
 xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<sheets><sheet name="deviceList_TRUE" sheetId="1" r:id="rId1"/></sheets></workbook>"""
    rels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
</Relationships>"""
    with zipfile.ZipFile(str(path), "w") as archive:
        archive.writestr("xl/workbook.xml", workbook)
        archive.writestr("xl/_rels/workbook.xml.rels", rels)
        archive.writestr("xl/worksheets/sheet1.xml", sheet)


class CleanTrueLinkOpticalLogsByTypeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.base = Path(self.temp.name)
        self.workbook = self.base / "UserInterface_Input.xlsx"
        self.logs = self.base / "Total_Log"
        self.logs.mkdir()
        make_workbook(self.workbook)
        self.old = self.logs / "[29924]10.185.89.146_AN-SPK08-2-SMP01003S01_HW-LLDP-Link_OPTIC_2026-08-08.txt"
        self.new = self.logs / "[2]10.85.159.146_AN-SPK08-2-SMP01003S01_HW-LLDP-Link_OPTIC_2026-08-13.txt"
        self.unrelated = self.logs / "[2]10.167.1.1_CPE-KEEP0001_HW-LLDP-Link_OPTIC_2026-08-13.txt"
        for path in (self.old, self.new, self.unrelated):
            path.write_text(path.name, encoding="utf-8")

    def tearDown(self):
        self.temp.cleanup()

    def run_cleaner(self, *extra):
        return subprocess.run(
            [
                sys.executable,
                str(SCRIPT),
                "--workbook",
                str(self.workbook),
                "--total-log-dir",
                str(self.logs),
                "--types",
                "PN,DN,AN",
            ] + list(extra),
            check=True,
            text=True,
            capture_output=True,
        )

    def test_keep_newest_matches_device_after_row_and_ip_change(self):
        dry_run = self.run_cleaner("--keep-newest")
        self.assertIn("device=2", dry_run.stdout)
        self.assertIn("Stale duplicate files: PN=0 DN=0 AN=1 total=1", dry_run.stdout)
        self.assertTrue(self.old.exists())

        self.run_cleaner("--keep-newest", "--apply")
        self.assertFalse(self.old.exists())
        self.assertTrue(self.new.exists())
        self.assertTrue(self.unrelated.exists())

    def test_primary_cleanup_removes_all_selected_device_snapshots(self):
        self.run_cleaner("--apply")
        self.assertFalse(self.old.exists())
        self.assertFalse(self.new.exists())
        self.assertTrue(self.unrelated.exists())

    def seed_budget(self, ip, value, day=None):
        root = self.base / "budget"
        if day is None:
            day = datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=7))).date().isoformat()
        counter = root / day / "counts" / (ip + ".properties")
        counter.parent.mkdir(parents=True, exist_ok=True)
        counter.write_text("used=" + value + "\n", encoding="utf-8")
        return root

    def test_primary_cleanup_preserves_exhausted_ip(self):
        budget = self.seed_budget("10.85.159.146", "3")
        self.run_cleaner("--daily-budget-dir", str(budget), "--apply")
        self.assertFalse(self.old.exists())
        self.assertTrue(self.new.exists())
        self.assertTrue(self.unrelated.exists())

    def test_all_cleanup_honors_quota_and_retains_unknown_identity(self):
        budget = self.seed_budget("10.167.1.1", "3")
        unknown = self.logs / "unknown.txt"
        unknown.write_text("keep")
        self.run_cleaner("--types", "ALL", "--daily-budget-dir", str(budget), "--apply")
        self.assertFalse(self.old.exists())
        self.assertFalse(self.new.exists())
        self.assertTrue(self.unrelated.exists())
        self.assertTrue(unknown.exists())

    def test_corrupt_budget_fails_before_deleting_any_log(self):
        budget = self.seed_budget("10.85.159.146", "bad")
        with self.assertRaises(subprocess.CalledProcessError):
            self.run_cleaner("--daily-budget-dir", str(budget), "--apply")
        self.assertTrue(all(p.exists() for p in (self.old, self.new, self.unrelated)))

    def test_previous_day_does_not_block_primary_cleanup(self):
        budget = self.seed_budget("10.85.159.146", "3", "2000-01-01")
        self.run_cleaner("--daily-budget-dir", str(budget), "--apply")
        self.assertFalse(self.new.exists())


if __name__ == "__main__":
    unittest.main()
