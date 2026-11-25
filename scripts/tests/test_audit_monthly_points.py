"""Point auditing preserves money and history, and cannot issue data mutations."""

import base64
from contextlib import redirect_stdout
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[1] / "audit-monthly-points.py"
SPEC = importlib.util.spec_from_file_location("audit_monthly_points", SCRIPT)
AUDIT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(AUDIT)


def entry(number, **changes):
    return dict(kind="ledger", id=number, userId="designer", userName="设计师", points="0.10",
                accountingMonth="2026-10", createdAt="2026-11-02T12:00:00.000000",
                confirmedAt="2026-11-02T12:00:00.000000", countInPerformance=1, source="TASK",
                sourceId=number, subTaskId=number, ruleCode="A:BASE", deliveryVersionId=number,
                userExists=1, taskExists=1, versionExists=1, sourceExists=1, versionTaskId=number) | changes


def schema():
    tables = {
        "point_ledgers": ("id", "user_id", "sub_task_id", "rule_code", "points", "accounting_month", "created_at", "confirmed_at", "delivery_version_id", "count_in_performance"),
        "point_adjustment_ledgers": ("id", "user_id", "source_type", "source_id", "points", "accounting_month", "created_at", "rule_code", "sub_task_id"),
        "users": ("user_id", "name"), "sub_tasks": ("id",), "sub_task_delivery_versions": ("id", "sub_task_id"),
        "point_appeals": ("id", "admin_reviewed_at"), "po_monthly_progress": ("id", "confirmed_at"),
    }
    return [dict(table=table, column=column, type="decimal" if column == "points" else "varchar",
                 scale=2 if column == "points" else None, nullable="YES")
            for table, columns in tables.items() for column in columns]


class MonthlyPointsAuditTest(unittest.TestCase):
    def test_original_decimal_values_and_months_are_preserved(self):
        rows = [entry(1), entry(2, points="0.20", countInPerformance=0),
                entry(3, kind="adjustment", source="MANUAL", sourceId=3, points="-0.05")]
        result = AUDIT.analyse(rows, schema(), "local-test")
        self.assertEqual("0.25", result["originalAllPoints"])
        self.assertEqual("0.05", result["originalPerformancePoints"])
        self.assertEqual("0.30", result["monthly"][0]["ledgerPoints"])
        self.assertEqual(3, result["anomalyCounts"]["ACCOUNTING_EVENT_MONTH_DIFFER"])
        self.assertTrue(all(row["accountingMonth"] == "2026-10" for row in result["entries"]))
        self.assertTrue(all(row["severity"] == "info" for row in result["anomalies"]))
        self.assertEqual(0, result["databaseWrites"])

    def test_missing_and_invalid_months_are_separate_without_inference(self):
        rows = [entry(1, accountingMonth=None), entry(2, accountingMonth=""), entry(3, accountingMonth="2026-13"),
                entry(4, accountingMonth="0000-01"), entry(5, accountingMonth=" 2026-10")]
        result = AUDIT.analyse(rows, schema(), "local-test")
        self.assertEqual(2, result["anomalyCounts"]["MISSING_ACCOUNTING_MONTH"])
        self.assertEqual(3, result["anomalyCounts"]["INVALID_ACCOUNTING_MONTH"])
        self.assertNotIn("ACCOUNTING_EVENT_MONTH_DIFFER", result["anomalyCounts"])
        self.assertEqual({None, "", "2026-13", "0000-01", " 2026-10"},
                         {row["accountingMonth"] for row in result["entries"]})
        self.assertEqual("0.50", result["originalAllPoints"])

    def test_duplicate_candidates_and_orphans_do_not_change_totals(self):
        first = entry(1, userExists=0, taskExists=0, versionExists=0, sourceExists=0)
        second = entry(2, subTaskId=1, sourceId=1, ruleCode="A:BASE", deliveryVersionId=1, versionTaskId=99)
        result = AUDIT.analyse([first, second], schema(), "local-test")
        for code in ("ORPHAN_USER", "ORPHAN_TASK", "ORPHAN_DELIVERY_VERSION", "ORPHAN_SOURCE",
                     "VERSION_TASK_MISMATCH", "DUPLICATE_UNIQUE_KEY", "MULTIPLE_VERSION_AWARDS_REVIEW"):
            self.assertEqual(1, result["anomalyCounts"][code], code)
        self.assertEqual("0.20", result["originalAllPoints"])
        self.assertEqual(2, len(result["entries"]))

    def test_precision_is_flagged_without_rounding(self):
        result = AUDIT.analyse([entry(1, points="1.005")], schema(), "local-test")
        self.assertEqual("1.005", result["originalAllPoints"])
        self.assertEqual("1.005", result["entries"][0]["points"])
        self.assertEqual(1, result["anomalyCounts"]["EXCESS_POINT_PRECISION"])
        for bad in ("NaN", "Infinity", "not a number", None):
            with self.subTest(value=bad), self.assertRaises(AUDIT.AuditError):
                AUDIT.analyse([entry(1, points=bad)], schema(), "local-test")

    def test_generated_queries_only_select_allowlisted_point_metadata(self):
        query = AUDIT.entry_query(schema())
        for value in (AUDIT.schema_query(), query):
            self.assertTrue(value.startswith("SELECT "))
            self.assertNotIn(";", value)
            self.assertNotRegex(value, r"\b(?:UPDATE|DELETE|INSERT|ALTER|DROP|CREATE|OUTFILE|LOAD_FILE)\b")
            self.assertNotIn("reason", value)
            self.assertNotIn("attachments", value)
            self.assertNotIn("session", value)
        self.assertIn("CAST(l.points AS CHAR)", query)
        older = [row for row in schema() if not (row["table"] == "point_adjustment_ledgers" and row["column"] == "sub_task_id")]
        older_query = AUDIT.entry_query(older)
        self.assertNotIn("l.sub_task_id", older_query.split("UNION ALL", 1)[1])
        for unsafe in ("SELECT 1; DELETE FROM point_ledgers", "SELECT 1 INTO OUTFILE '/tmp/points'", "SELECT GET_LOCK('points',1)"):
            with self.subTest(query=unsafe), self.assertRaises(AUDIT.AuditError):
                AUDIT.read_query(unsafe)

    def test_remote_reader_enforces_profile_read_only_and_never_exposes_credentials(self):
        secret = "fixture secret not printed"
        commands = []
        app = {"Config": {"Env": ["SPRING_PROFILES_ACTIVE=prod", "DESIGNPM_DB_USER=fixture",
                                     "DESIGNPM_DB_NAME=fixture", "DESIGNPM_DB_PASSWORD=" + secret]}}

        def run(arguments, **kwargs):
            commands.append((arguments, kwargs))
            if arguments[:2] == ["docker", "inspect"]:
                return subprocess.CompletedProcess(arguments, 0, json.dumps([app]).encode(), b"")
            if arguments[:3] == ["docker", "exec", "-i"]:
                self.assertNotIn(secret, " ".join(arguments))
                self.assertIn("SET SESSION TRANSACTION READ ONLY", " ".join(arguments))
                self.assertEqual((secret + "\nSELECT 1\n").encode(), kwargs["input"])
                return subprocess.CompletedProcess(arguments, 0, b'{"value":1}\n', b"")
            self.fail("unexpected database operation")

        parameters = {"query": "SELECT 1", "production": True, "mysql_container": "fixture-mysql"}
        encoded = base64.b64encode(json.dumps(parameters).encode()).decode()
        # The remote code writes bytes to stdout.buffer.
        output = io.BytesIO()
        wrapper = io.TextIOWrapper(output, encoding="utf-8", write_through=True)
        with patch.object(sys, "argv", ["remote", encoded]), patch.object(subprocess, "run", side_effect=run), \
                redirect_stdout(wrapper):
            exec(compile(AUDIT.REMOTE_SCRIPT, "points-audit-remote", "exec"), {})
        self.assertEqual(b'{"value":1}\n', output.getvalue())
        self.assertEqual(2, len(commands))
        self.assertFalse(any("redis" in " ".join(command) for command, _ in commands))
        self.assertNotIn(secret.encode(), output.getvalue())

    def test_local_is_default_and_failures_redact_details(self):
        output = io.StringIO()
        with patch.object(AUDIT, "production_config") as configuration, \
                patch.object(AUDIT, "read_query", side_effect=RuntimeError("private credential")), redirect_stdout(output):
            self.assertEqual(1, AUDIT.main([]))
        configuration.assert_not_called()
        self.assertEqual({"ok": False, "readOnly": True, "failedPhase": "schema_read", "error": "points_audit_failed"},
                         json.loads(output.getvalue()))

    def test_reports_are_private_exact_and_safe_to_open_in_a_spreadsheet(self):
        report = AUDIT.analyse([entry(1, userName="=BAD()", points="-1.25")], schema(), "local-test")
        with tempfile.TemporaryDirectory(prefix="emie-points-audit-test-") as temporary:
            folder = Path(temporary) / "report"
            files = AUDIT.write_report(report, folder)
            self.assertEqual(0o700, folder.stat().st_mode & 0o777)
            self.assertEqual(0o600, Path(files["audit"]).stat().st_mode & 0o777)
            content = Path(files["entries"]).read_text(encoding="utf-8-sig")
            self.assertIn("'=BAD()", content)
            self.assertIn("-1.25", content)
            self.assertEqual(report, json.loads(Path(files["audit"]).read_text()))
            with self.assertRaises(FileExistsError):
                AUDIT.write_report(report, folder)


if __name__ == "__main__":
    unittest.main()
