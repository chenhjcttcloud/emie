#!/usr/bin/env python3
"""只读整理积分：默认本地测试库；生产必须显式 --production-read-only。

保留每笔原分数及有效归属月，不更新数据库。跨确认月只提示；缺失或非法月份
单独列出，不按时间猜测回填。只读取积分元数据、人员名称和关联是否存在。
"""

import argparse
import base64
from collections import Counter, defaultdict
import csv
from decimal import Decimal, InvalidOperation
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import tempfile


ROOT = Path(__file__).resolve().parents[1]
TABLES = (
    "point_ledgers", "point_adjustment_ledgers", "users", "sub_tasks",
    "sub_task_delivery_versions", "design_requirements", "task_withdrawals",
    "point_appeals", "po_monthly_progress", "projects", "material_market_items",
)
MONTH = re.compile(r"[0-9]{4}-(?:0[1-9]|1[0-2])\Z")
REMOTE_SCRIPT = r'''
import base64, json, re, subprocess, sys

def run(arguments, **kwargs):
    result = subprocess.run(arguments, capture_output=True, timeout=90, **kwargs)
    if result.returncode:
        raise RuntimeError("database_read_failed")
    return result.stdout

try:
    parameters = json.loads(base64.b64decode(sys.argv[1]))
    query = parameters["query"]
    # The caller only supplies generated SELECTs; this also rejects multiple statements.
    forbidden = r"\b(?:INSERT|UPDATE|DELETE|ALTER|DROP|CREATE|TRUNCATE|REPLACE|CALL|DO|HANDLER|OUTFILE|DUMPFILE|LOAD_FILE|SLEEP|BENCHMARK|GET_LOCK|RELEASE_LOCK)\b"
    if not query.lstrip().startswith("SELECT ") or any(token in query for token in (";", "--", "/*")) or re.search(forbidden, query, re.I):
        raise RuntimeError("query_not_read_only")
    application = "emie-app" if parameters["production"] else "emie-test-app"
    app = json.loads(run(["docker", "inspect", application]))[0]
    env = dict(value.split("=", 1) for value in app["Config"]["Env"] if "=" in value)
    profile = env.get("SPRING_PROFILES_ACTIVE", "")
    if parameters["production"]:
        if profile != "prod":
            raise RuntimeError("production_profile_invalid")
        prefix = "DESIGNPM_DB_"
    else:
        if profile not in {"dev", "local"}:
            raise RuntimeError("local_profile_invalid")
        prefix = "DESIGNPM_TEST_DB_"
    user, password, database = (env[prefix + key] for key in ("USER", "PASSWORD", "NAME"))
    if not re.fullmatch(r"[A-Za-z0-9_]+", database) or not re.fullmatch(r"[A-Za-z0-9_.-]+", user):
        raise RuntimeError("database_identity_invalid")
    if "\n" in password or "\r" in password:
        raise RuntimeError("database_password_format_invalid")
    container = parameters.get("mysql_container")
    if not container:
        rows = run(["docker", "ps", "--format", "{{.Names}}\t{{.Image}}"], text=True).splitlines()
        matches = [row.split("\t", 1)[0] for row in rows if "mysql" in row.split("\t", 1)[-1].lower()]
        if len(matches) != 1:
            raise RuntimeError("mysql_container_ambiguous")
        container = matches[0]
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*", container):
        raise RuntimeError("mysql_container_invalid")
    # Credentials travel on stdin, never in argv or the report. READ ONLY is session-only.
    shell = 'IFS= read -r MYSQL_PWD; export MYSQL_PWD; exec mysql --batch --raw --skip-column-names --default-character-set=utf8mb4 --init-command="SET SESSION TRANSACTION READ ONLY" -u "$1" "$2"'
    output = run(["docker", "exec", "-i", container, "sh", "-c", shell, "points-audit", user, database],
                 input=(password + "\n" + query + "\n").encode())
    if len(output) > 128 * 1024 * 1024:
        raise RuntimeError("database_result_too_large")
    sys.stdout.buffer.write(output)
except Exception:
    print('{"auditReadError":"database_read_failed"}')
    sys.exit(1)
'''


class AuditError(Exception):
    """Only fixed error codes may be shown to the user."""


def production_config(root):
    path = Path(os.environ.get("PRODUCTION_ENV_FILE", str(root / ".server.production.local.env")))
    if not path.is_absolute():
        path = root / path
    if path.is_file():
        result = subprocess.run(["bash", "-c", 'set -ae; source "$1" >/dev/null; env -0',
                                 "points-audit-config", str(path)], capture_output=True, timeout=10, check=True)
        environment = dict(value.decode().split("=", 1) for value in result.stdout.split(b"\0") if b"=" in value)
    else:
        environment = os.environ
    fields = ("SERVER_ROLE", "SERVER_HOST", "SERVER_USER", "SERVER_PORT", "SERVER_PASSWORD",
              "SERVER_SUDO_PASSWORD", "SERVER_MYSQL_CONTAINER")
    config = {name: environment.get(name, "") for name in fields}
    if config["SERVER_ROLE"] != "production-local" or not config["SERVER_PASSWORD"]:
        raise AuditError("production_configuration_invalid")
    if not re.fullmatch(r"[A-Za-z0-9.-]+", config["SERVER_HOST"]) \
            or not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_-]*", config["SERVER_USER"]):
        raise AuditError("production_configuration_invalid")
    if not config["SERVER_PORT"].isdigit() or not 1 <= int(config["SERVER_PORT"]) <= 65535:
        raise AuditError("production_configuration_invalid")
    if config["SERVER_MYSQL_CONTAINER"] and not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*", config["SERVER_MYSQL_CONTAINER"]):
        raise AuditError("production_configuration_invalid")
    return config


def read_query(query, config=None):
    forbidden = r"\b(?:INSERT|UPDATE|DELETE|ALTER|DROP|CREATE|TRUNCATE|REPLACE|CALL|DO|HANDLER|OUTFILE|DUMPFILE|LOAD_FILE|SLEEP|BENCHMARK|GET_LOCK|RELEASE_LOCK)\b"
    if not query.lstrip().startswith("SELECT ") or any(value in query for value in (";", "--", "/*")) \
            or re.search(forbidden, query, re.I):
        raise AuditError("query_not_read_only")
    parameters = {"query": query, "production": config is not None,
                  "mysql_container": config.get("SERVER_MYSQL_CONTAINER", "") if config else "emie-test-mysql"}
    encoded = base64.b64encode(json.dumps(parameters).encode()).decode()
    if config:
        command = ["sshpass", "-e", "ssh", "-p", config["SERVER_PORT"],
                   "-o", "StrictHostKeyChecking=accept-new", "-o", "ConnectTimeout=8",
                   "-o", "ServerAliveInterval=10", "-o", "ServerAliveCountMax=3",
                   config["SERVER_USER"] + "@" + config["SERVER_HOST"],
                   "sudo -S -p '' python3 -c " + shlex.quote(REMOTE_SCRIPT) + " " + shlex.quote(encoded)]
        result = subprocess.run(command, input=(config["SERVER_SUDO_PASSWORD"] or config["SERVER_PASSWORD"]) + "\n",
                                env={**os.environ, "SSHPASS": config["SERVER_PASSWORD"]},
                                capture_output=True, text=True, timeout=120)
    else:
        result = subprocess.run([sys.executable, "-c", REMOTE_SCRIPT, encoded], capture_output=True,
                                text=True, timeout=100)
    if result.returncode:
        raise AuditError("database_read_failed")
    try:
        rows = [json.loads(line) for line in result.stdout.splitlines() if line.strip()]
    except (ValueError, RecursionError):
        raise AuditError("database_result_invalid") from None
    if any(not isinstance(row, dict) or "auditReadError" in row for row in rows):
        raise AuditError("database_result_invalid")
    return rows


def schema_query():
    tables = ",".join("'" + table + "'" for table in TABLES)
    return ("SELECT JSON_OBJECT('table',TABLE_NAME,'column',COLUMN_NAME,'type',DATA_TYPE,"
            "'scale',NUMERIC_SCALE,'nullable',IS_NULLABLE) FROM information_schema.COLUMNS "
            "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN (" + tables + ") ORDER BY TABLE_NAME,ORDINAL_POSITION")


def entry_query(schema):
    columns = defaultdict(set)
    for row in schema:
        columns[row["table"]].add(row["column"])
    for table in ("point_ledgers", "point_adjustment_ledgers"):
        if not {"id", "user_id", "points", "created_at", "accounting_month"} <= columns[table]:
            raise AuditError("point_schema_incomplete")
    if not {"user_id", "name"} <= columns["users"]:
        raise AuditError("point_schema_incomplete")

    def optional(table, alias, column, fallback="NULL"):
        return alias + "." + column if column in columns[table] else fallback

    def exists(table, expression):
        return "EXISTS(SELECT 1 FROM " + table + " r WHERE r.id=" + expression + ")" if "id" in columns[table] else "NULL"

    def date(expression):
        return "DATE_FORMAT(" + expression + ",'%Y-%m-%dT%H:%i:%s.%f')"

    def json_select(fields, tail):
        return "SELECT JSON_OBJECT(" + ",".join("'" + key + "'," + value for key, value in fields.items()) + ") " + tail

    version = optional("point_ledgers", "l", "delivery_version_id")
    confirmed = optional("point_ledgers", "l", "confirmed_at")
    version_task = ("(SELECT r.sub_task_id FROM sub_task_delivery_versions r WHERE r.id=" + version + ")"
                    if "sub_task_id" in columns["sub_task_delivery_versions"] else "NULL")
    base = dict(kind="'ledger'", id="l.id", userId="l.user_id", userName="u.name",
                points="CAST(l.points AS CHAR)", accountingMonth="l.accounting_month",
                createdAt=date("l.created_at"), confirmedAt=date(confirmed),
                countInPerformance=optional("point_ledgers", "l", "count_in_performance", "TRUE"),
                source="'TASK'", sourceId="l.sub_task_id", subTaskId="l.sub_task_id",
                ruleCode="l.rule_code", deliveryVersionId=version, userExists="u.user_id IS NOT NULL",
                taskExists=exists("sub_tasks", "l.sub_task_id"), versionExists=exists("sub_task_delivery_versions", version),
                versionTaskId=version_task, sourceExists=exists("sub_tasks", "l.sub_task_id"))
    ledger = json_select(base, "FROM point_ledgers l LEFT JOIN users u ON u.user_id=l.user_id")
    sources = {"DESIGN_REQUIREMENT": "design_requirements", "TASK_WITHDRAWAL": "task_withdrawals",
               "APPEAL": "point_appeals", "PO_PROGRESS": "po_monthly_progress", "MATERIAL_MARKET": "projects",
               "MATERIAL_IDEA_PUBLISH": "material_market_items", "MATERIAL_FREE_RATING": "material_market_items"}
    source_exists = "CASE l.source_type WHEN 'MANUAL' THEN TRUE " + " ".join(
        "WHEN '" + source + "' THEN " + exists(table, "l.source_id") for source, table in sources.items()) + " ELSE NULL END"
    confirmations = []
    for source, table, column in (("PO_PROGRESS", "po_monthly_progress", "confirmed_at"),
                                  ("APPEAL", "point_appeals", "admin_reviewed_at")):
        if column in columns[table]:
            confirmations.append("WHEN '" + source + "' THEN (SELECT r." + column + " FROM " + table + " r WHERE r.id=l.source_id)")
    adjustment_confirmed = "CASE l.source_type WHEN 'MANUAL' THEN l.created_at " + " ".join(confirmations) + " ELSE NULL END"
    task = optional("point_adjustment_ledgers", "l", "sub_task_id")
    inferred_tasks = []
    if "sub_task_id" in columns["task_withdrawals"]:
        inferred_tasks.append("WHEN 'TASK_WITHDRAWAL' THEN (SELECT r.sub_task_id FROM task_withdrawals r WHERE r.id=l.source_id)")
    if "point_ledger_id" in columns["point_appeals"]:
        inferred_tasks.append("WHEN 'APPEAL' THEN (SELECT p.sub_task_id FROM point_appeals r LEFT JOIN point_ledgers p ON p.id=r.point_ledger_id WHERE r.id=l.source_id)")
    if inferred_tasks:
        task = "COALESCE(" + task + ",CASE l.source_type " + " ".join(inferred_tasks) + " ELSE NULL END)"
    adjustment = {**base, "kind": "'adjustment'", "source": "l.source_type", "sourceId": "l.source_id",
                  "subTaskId": task, "ruleCode": optional("point_adjustment_ledgers", "l", "rule_code"),
                  "confirmedAt": date(adjustment_confirmed), "countInPerformance": "TRUE", "deliveryVersionId": "NULL",
                  "taskExists": exists("sub_tasks", task), "versionExists": "NULL", "versionTaskId": "NULL",
                  "sourceExists": source_exists}
    return ledger + " UNION ALL " + json_select(adjustment, "FROM point_adjustment_ledgers l LEFT JOIN users u ON u.user_id=l.user_id")


def month_status(value):
    if value is None or not str(value).strip():
        return "missing"
    return "valid" if MONTH.fullmatch(str(value)) and not str(value).startswith("0000-") else "invalid"


def amount(value):
    try:
        number = Decimal(str(value))
    except (InvalidOperation, ValueError):
        raise AuditError("point_amount_invalid") from None
    if not number.is_finite():
        raise AuditError("point_amount_invalid")
    return number


def decimal_text(value):
    # Do not quantize: excess precision is reported without silently changing historical values.
    return format(value, ".2f") if value == value.quantize(Decimal("0.01")) else format(value, "f")


def analyse(entries, schema, environment):
    groups, duplicate_keys, versions = {}, defaultdict(list), defaultdict(list)
    anomalies, details = [], []

    def issue(code, row, severity="warning", **extra):
        anomalies.append(dict(code=code, severity=severity, entry=row["entry"],
                              userId=row.get("userId"), accountingMonth=row.get("accountingMonth"),
                              points=row.get("points"), **extra))

    for original in entries:
        row = dict(original)
        if row.get("kind") not in {"ledger", "adjustment"}:
            raise AuditError("entry_kind_invalid")
        row["entry"] = ("L-" if row["kind"] == "ledger" else "A-") + str(row["id"])
        points = amount(row.get("points"))
        row["points"] = decimal_text(points)
        row["monthStatus"] = month_status(row.get("accountingMonth"))
        event = row.get("confirmedAt") or row.get("createdAt")
        row["eventMonth"] = event[:7] if isinstance(event, str) and month_status(event[:7]) == "valid" else None
        row["eventBasis"] = "confirmedAt" if row.get("confirmedAt") else "createdAt"
        if row["monthStatus"] != "valid":
            issue("MISSING_ACCOUNTING_MONTH" if row["monthStatus"] == "missing" else "INVALID_ACCOUNTING_MONTH", row)
        elif row["eventMonth"] and row["accountingMonth"] != row["eventMonth"]:
            issue("ACCOUNTING_EVENT_MONTH_DIFFER", row, "info", eventMonth=row["eventMonth"], eventBasis=row["eventBasis"],
                  action="preserve_valid_accounting_month_review_only")
        if points != points.quantize(Decimal("0.01")):
            issue("EXCESS_POINT_PRECISION", row)
        for field, identifier, code in (("userExists", "userId", "ORPHAN_USER"),
                                        ("taskExists", "subTaskId", "ORPHAN_TASK"),
                                        ("versionExists", "deliveryVersionId", "ORPHAN_DELIVERY_VERSION"),
                                        ("sourceExists", "sourceId", "ORPHAN_SOURCE")):
            if row.get(identifier) is not None and row.get(field) in (False, 0):
                issue(code, row)
        if row.get("deliveryVersionId") is not None and row.get("versionTaskId") is not None \
                and row["versionTaskId"] != row.get("subTaskId"):
            issue("VERSION_TASK_MISMATCH", row)
        if row.get("sourceExists") is None:
            issue("SOURCE_ASSOCIATION_UNCHECKED", row, "info")
        key = (row.get("accountingMonth"), row.get("userId"))
        group = groups.setdefault(key, dict(accountingMonth=key[0], monthStatus=row["monthStatus"], userId=key[1],
            userName=row.get("userName"), recordCount=0, ledgerPoints=Decimal(0), performanceLedgerPoints=Decimal(0),
            adjustmentPoints=Decimal(0), allPoints=Decimal(0), performancePoints=Decimal(0),
            positivePoints=Decimal(0), negativePoints=Decimal(0), sources=defaultdict(Decimal)))
        group["recordCount"] += 1
        group["allPoints"] += points
        group["positivePoints" if points > 0 else "negativePoints"] += points
        group["sources"][row.get("source") or "UNKNOWN"] += points
        if row["kind"] == "ledger":
            group["ledgerPoints"] += points
            if row.get("countInPerformance"):
                group["performanceLedgerPoints"] += points
                group["performancePoints"] += points
            duplicate_keys[("ledger", row.get("userId"), row.get("subTaskId"), row.get("ruleCode"))].append(row)
            if row.get("deliveryVersionId") is not None:
                versions[(row.get("userId"), row.get("subTaskId"), row.get("deliveryVersionId"))].append(row)
        else:
            group["adjustmentPoints"] += points
            group["performancePoints"] += points
            duplicate_keys[("adjustment", row.get("source"), row.get("sourceId"))].append(row)
        details.append(row)
    for code, candidates in (("DUPLICATE_UNIQUE_KEY", duplicate_keys), ("MULTIPLE_VERSION_AWARDS_REVIEW", versions)):
        for rows in candidates.values():
            if len(rows) > 1:
                issue(code, rows[0], entries=[row["entry"] for row in rows], action="review_only_no_automatic_deduction")
    schema_anomalies = []
    for column in schema:
        if column.get("table") in {"point_ledgers", "point_adjustment_ledgers"} and column.get("column") == "points":
            if column.get("type") != "decimal" or column.get("scale") != 2:
                schema_anomalies.append(dict(code="POINT_SCHEMA_NOT_DECIMAL_2", **column))
    monthly = []
    for group in sorted(groups.values(), key=lambda row: (str(row["accountingMonth"] or ""), str(row["userId"]))):
        monthly.append({key: ({name: decimal_text(value) for name, value in sorted(item.items())} if key == "sources"
                              else decimal_text(item) if isinstance(item, Decimal) else item)
                        for key, item in group.items()})
    details.sort(key=lambda row: (str(row.get("accountingMonth") or ""), str(row.get("userId")), row["entry"]))
    counts = Counter(item["code"] for item in anomalies)
    return dict(environment=environment, readOnly=True, databaseWrites=0,
                policy="preserve_existing_points_and_valid_accounting_months; missing_or_invalid_months_are_not_inferred",
                recordCount=len(details), originalAllPoints=decimal_text(sum((amount(row["points"]) for row in details), Decimal(0))),
                originalPerformancePoints=decimal_text(sum((amount(row["performancePoints"]) for row in monthly), Decimal(0))),
                anomalyCounts=dict(sorted(counts.items())), schemaAnomalies=schema_anomalies,
                monthly=monthly, entries=details, anomalies=anomalies)


def write_report(report, output):
    output.mkdir(parents=True, exist_ok=False, mode=0o700)
    files = {}
    for name, rows in (("monthly", report["monthly"]), ("entries", report["entries"]), ("anomalies", report["anomalies"])):
        path = output / (name + ".csv")
        fields = list(dict.fromkeys(key for row in rows for key in row))
        with path.open("w", newline="", encoding="utf-8-sig") as stream:
            os.chmod(path, 0o600)
            writer = csv.DictWriter(stream, fieldnames=fields)
            writer.writeheader()
            for row in rows:
                values = {key: json.dumps(value, ensure_ascii=False, sort_keys=True) if isinstance(value, (dict, list))
                          else value for key, value in row.items()}
                # Spreadsheet programs must not execute names or rule codes as formulas.
                writer.writerow({key: "'" + value if isinstance(value, str) and value.startswith(("=", "+", "-", "@", "\t", "\r"))
                                 and key not in {"points", "ledgerPoints", "performanceLedgerPoints", "adjustmentPoints", "allPoints", "performancePoints", "positivePoints", "negativePoints"}
                                 else value for key, value in values.items()})
        files[name] = str(path)
    path = output / "audit.json"
    with path.open("w", encoding="utf-8") as stream:
        os.chmod(path, 0o600)
        json.dump(report, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    files["audit"] = str(path)
    return files


def main(arguments=None):
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    parser.add_argument("--production-read-only", action="store_true", help="显式允许只读查询生产积分，不执行验收或发布")
    parser.add_argument("--output", type=Path, help="输出到尚不存在的目录；默认写入系统临时目录")
    args = parser.parse_args(arguments)
    phase = "configuration"
    try:
        config = production_config(ROOT) if args.production_read_only else None
        phase = "schema_read"
        schema = read_query(schema_query(), config)
        phase = "point_read"
        entries = read_query(entry_query(schema), config)
        phase = "analyse"
        report = analyse(entries, schema, "production" if args.production_read_only else "local-test")
        phase = "write_report"
        output = args.output
        if output is None:
            output = Path(tempfile.mkdtemp(prefix="emie-monthly-points-")) / "report"
        files = write_report(report, output)
        print(json.dumps(dict(ok=True, readOnly=True, databaseWrites=0, environment=report["environment"],
                              recordCount=report["recordCount"], originalAllPoints=report["originalAllPoints"],
                              originalPerformancePoints=report["originalPerformancePoints"],
                              anomalyCounts=report["anomalyCounts"], files=files), ensure_ascii=False))
        return 0
    except Exception:
        # Never echo database errors, SSH stderr, credentials, session data or raw commands.
        print(json.dumps(dict(ok=False, readOnly=True, failedPhase=phase, error="points_audit_failed")))
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
