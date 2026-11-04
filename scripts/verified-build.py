#!/usr/bin/env python3
"""Reuse a Maven artifact only when its inputs, toolchain and receipt still agree."""

import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import stat
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET


INPUTS = ("src", "pom.xml", "mvnw", ".mvn", "db", "scripts")
EXCLUDED = {"node_modules", "__pycache__", ".pytest_cache", ".mypy_cache", ".ruff_cache", ".cache"}
EXCLUDED_PATHS = {"scripts/modal-e2e/test-results"}
BUILD_ENV = (
    "JAVA_HOME", "JAVA21_HOME", "JAVA_OPTS", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS",
    "MAVEN_HOME", "M2_HOME", "MAVEN_USER_HOME", "MAVEN_OPTS", "MAVEN_ARGS", "MAVEN_CMD_LINE_ARGS",
    "PATH", "CLASSPATH", "LANG", "LC_ALL", "TZ",
)
COMMANDS = {
    "package": ["scripts/mvnw-java21.sh", "clean", "package", "-DskipTests", "-q"],
    "full": ["scripts/mvnw-java21.sh", "clean", "package"],
}
SCHEMA = 1


def assert_full_configuration(root, config_paths=()):
    settings = Path(os.environ.get("MAVEN_USER_HOME", str(Path.home() / ".m2")))
    arguments = [os.environ.get(key, "") for key in BUILD_ENV if key not in {"PATH", "CLASSPATH"}]
    arguments.extend(path.read_text() for path in (root / ".mvn/maven.config", root / ".mvn/jvm.config") if path.is_file())
    properties = r"(?:test|it\.test|groups|excludedGroups|skip[^\s=]*|maven\.test\.[^\s=]*|surefire\.[^\s=]*|failsafe\.[^\s=]*)"
    for value in arguments:
        try:
            value = " ".join(shlex.split(value, comments=True))
        except ValueError:
            raise RuntimeError("Maven 构建参数包含无效的引号") from None
        value = re.sub(r"--define(?:=|\s+)", "-D", value)
        if (re.search(r"(?:^|[\s\"'])-D\s*" + properties + r"(?:=|\s|$)", value, re.IGNORECASE)
                or re.search(r"(?:^|\s)(?:-f|-pl|-rf|-s|-gs|--file|--projects|--resume-from|--settings|--global-settings)(?:=|\s|$)", value)):
            raise RuntimeError("全量构建不允许外部测试筛选、跳过或替换项目配置，请清除相关 Maven 参数")
    for path in {settings / "settings.xml", settings / "toolchains.xml", *config_paths}:
        if not path.is_file():
            continue
        try:
            document = ET.parse(path).getroot()
        except ET.ParseError:
            raise RuntimeError("Maven 配置 XML 无效") from None
        for element in document.iter():
            if element.tag.rsplit("}", 1)[-1] != "properties":
                continue
            if any(re.fullmatch(properties, child.tag.rsplit("}", 1)[-1], re.IGNORECASE) for child in element):
                raise RuntimeError("全量构建不允许 Maven 配置中的测试筛选或跳过属性")


def file_hash(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def source_hash(root):
    digest = hashlib.sha256()

    def add(path):
        relative = path.relative_to(root).as_posix()
        if (relative in EXCLUDED_PATHS or path.name in EXCLUDED or path.name == ".DS_Store"
                or path.suffix in {".pyc", ".pyo"}):
            return
        digest.update(relative.encode() + b"\0")
        if not path.exists() and not path.is_symlink():
            digest.update(b"missing\0")
            return
        attributes = path.lstat()
        if stat.S_ISLNK(attributes.st_mode):
            raise RuntimeError("构建输入包含符号链接，请使用普通源码文件")
        if stat.S_ISDIR(attributes.st_mode):
            digest.update(b"directory\0")
            for child in sorted(path.iterdir()):
                add(child)
        elif stat.S_ISREG(attributes.st_mode):
            digest.update(str(stat.S_IMODE(attributes.st_mode)).encode() + b"\0")
            digest.update(file_hash(path).encode() + b"\0")
        else:
            raise RuntimeError("构建输入包含不支持的文件类型")

    for name in INPUTS:
        add(root / name)
    return digest.hexdigest()


def toolchain_hash(root, mode):
    try:
        result = subprocess.run(
            [str(root / "scripts/mvnw-java21.sh"), "--version"], cwd=root,
            capture_output=True, timeout=30, check=True,
        )
    except (OSError, subprocess.SubprocessError):
        raise RuntimeError("无法核对 Java 与 Maven 版本") from None
    version = re.sub(rb"\x1b\[[0-9;]*m", b"", result.stdout + result.stderr)
    if not re.search(rb"Apache Maven\s+\S+", version) or not re.search(rb"Java version:\s*21(?:[.\s,]|$)", version):
        raise RuntimeError("构建入口未报告有效的 Maven 和 Java 21 版本")
    settings = Path(os.environ.get("MAVEN_USER_HOME", str(Path.home() / ".m2")))
    config_paths = {settings / "settings.xml", settings / "toolchains.xml"}
    maven_home = re.search(rb"(?m)^Maven home:\s*(.+)$", version)
    if maven_home:
        config_paths.add(Path(os.fsdecode(maven_home.group(1).strip())) / "conf/settings.xml")
    if mode == "full":
        assert_full_configuration(root, config_paths)
    metadata = {
        "version_sha256": hashlib.sha256(version).hexdigest(),
        "environment": {key: os.environ.get(key) for key in BUILD_ENV},
        "configuration": {str(path): file_hash(path) if path.is_file() else None for path in sorted(config_paths)},
        "helper_sha256": file_hash(Path(__file__)),
    }
    return hashlib.sha256(json.dumps(metadata, sort_keys=True).encode()).hexdigest()


def test_reports(root, started_ns=0):
    reports = sorted((root / "target/surefire-reports").glob("TEST-*.xml"))
    if not reports:
        raise ValueError("没有全量测试报告")
    total = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    digests = {}
    for path in reports:
        if path.stat().st_mtime_ns < started_ns or path.stat().st_size > 16 * 1024 * 1024:
            raise ValueError("测试报告过时或无效")
        suite = ET.parse(path).getroot()
        if suite.tag != "testsuite":
            raise ValueError("测试报告格式无效")
        counts = {key: int(suite.attrib.get(key, "0")) for key in total}
        cases = suite.findall("testcase")
        if any(count < 0 for count in counts.values()) or counts["tests"] != len(cases):
            raise ValueError("测试报告计数无效")
        for key, tag in (("failures", "failure"), ("errors", "error"), ("skipped", "skipped")):
            if counts[key] != sum(case.find(tag) is not None for case in cases):
                raise ValueError("测试报告结果不一致")
        for key in total:
            total[key] += counts[key]
        digests[path.name] = file_hash(path)
    if total["failures"] or total["errors"] or total["tests"] <= total["skipped"]:
        raise ValueError("全量测试未通过或没有实际执行的测试")
    return {**total, "reports": digests}


def read_receipt(path, root, jar, source, toolchain, mode):
    try:
        receipt = json.loads(path.read_text())
        recorded_mode = receipt["mode"]
        if (receipt["schema"] != SCHEMA or receipt["source_sha256"] != source
                or receipt["toolchain_sha256"] != toolchain or receipt["command"] != COMMANDS[recorded_mode]
                or (mode == "full" and recorded_mode != "full") or not jar.is_file()
                or receipt["jar_sha256"] != file_hash(jar)):
            return None
        if recorded_mode == "full" and receipt["tests"] != test_reports(root):
            return None
        return receipt
    except (OSError, ValueError, KeyError, TypeError, ET.ParseError):
        return None


def write_receipt(path, receipt):
    with tempfile.NamedTemporaryFile("w", dir=path.parent, prefix="receipt-", delete=False) as stream:
        temporary = Path(stream.name)
        try:
            json.dump(receipt, stream, sort_keys=True)
            stream.flush()
            os.fsync(stream.fileno())
            os.replace(temporary, path)
        finally:
            temporary.unlink(missing_ok=True)


def copy_artifact(jar, output, expected_hash):
    if output.parent.resolve() / output.name == jar.resolve():
        return
    if output.suffix.lower() != ".jar":
        raise RuntimeError("产物副本路径必须以 .jar 结尾")
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=output.parent, prefix="verified-jar-", delete=False) as stream:
        temporary = Path(stream.name)
    try:
        shutil.copyfile(jar, temporary)
        if file_hash(temporary) != expected_hash:
            raise RuntimeError("产物复制校验失败")
        os.replace(temporary, output)
    finally:
        temporary.unlink(missing_ok=True)


def build(root, mode, output, check_only=False):
    started = time.monotonic()
    work = root / ".codex-build"
    work.mkdir(exist_ok=True)
    receipt_path = work / "verified-build.json"
    jar = root / "target/design-pm-1.0.0.jar"
    descriptor = os.open(work / "verified-build.lock", os.O_CREAT | os.O_RDWR, 0o600)
    with os.fdopen(descriptor, "a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        if mode == "full":
            assert_full_configuration(root)
        source = source_hash(root)
        toolchain = toolchain_hash(root, mode)
        receipt = read_receipt(receipt_path, root, jar, source, toolchain, mode)
        reused = receipt is not None
        if not reused:
            if check_only:
                raise RuntimeError("没有与当前源码、工具链和应用产物匹配的构建收据")
            receipt_path.unlink(missing_ok=True)
            print("正在构建并验证应用产物…", file=sys.stderr, flush=True)
            log_path = work / "verified-build.log"
            descriptor = os.open(log_path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
            with os.fdopen(descriptor, "wb") as log:
                os.fchmod(log.fileno(), 0o600)
                started_ns = time.time_ns()
                result = subprocess.run([str(root / COMMANDS[mode][0]), *COMMANDS[mode][1:]], cwd=root,
                                        stdout=log, stderr=subprocess.STDOUT)
            if result.returncode:
                raise RuntimeError("Maven 构建失败，请检查 .codex-build/verified-build.log")
            if source != source_hash(root) or toolchain != toolchain_hash(root, mode):
                raise RuntimeError("构建期间源码或工具链发生变化，请重新构建")
            if not jar.is_file():
                raise RuntimeError("构建未生成应用 JAR")
            try:
                reports = test_reports(root, started_ns) if mode == "full" else None
            except (OSError, ValueError, ET.ParseError):
                raise RuntimeError("全量测试报告未通过验证，请检查构建日志") from None
            receipt = {
                "schema": SCHEMA, "mode": mode, "source_sha256": source, "toolchain_sha256": toolchain,
                "command": COMMANDS[mode], "jar_sha256": file_hash(jar), "tests": reports,
            }
            write_receipt(receipt_path, receipt)
        if source != source_hash(root) or receipt["jar_sha256"] != file_hash(jar):
            receipt_path.unlink(missing_ok=True)
            raise RuntimeError("复用前源码或应用产物发生变化，请重新构建")
        if output is not None:
            copy_artifact(jar, output, receipt["jar_sha256"])
        elapsed = time.monotonic() - started
        print("已复用验证产物" if reused else "构建与产物验证完成", f"，耗时 {elapsed:.2f} 秒", sep="", file=sys.stderr)
        values = {
            "verified_build": "reused" if reused else "built", "build_mode": receipt["mode"],
            "source_sha256": source, "toolchain_sha256": toolchain, "jar_sha256": receipt["jar_sha256"],
            "jar_path": str(output if output is not None else jar), "build_seconds": f"{elapsed:.3f}",
        }
        if receipt["mode"] == "full":
            values["build_tests"] = receipt["tests"]["tests"]
            values["build_skipped"] = receipt["tests"]["skipped"]
        for key, value in values.items():
            print(f"{key}={value}")


def main():
    parser = argparse.ArgumentParser(description="校验并复用 Maven 构建产物")
    parser.add_argument("--source-root", required=True, type=Path)
    parser.add_argument("--mode", required=True, choices=COMMANDS)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--check-only", action="store_true", help="只校验已有构建收据，不执行构建")
    args = parser.parse_args()
    if args.check_only and args.output is not None:
        parser.error("--check-only 不能与 --output 同时使用")
    root = args.source_root.expanduser().resolve()
    output = args.output.expanduser().absolute() if args.output is not None else None
    try:
        if not root.is_dir() or not (root / "pom.xml").is_file():
            raise RuntimeError("构建源码目录无效")
        build(root, args.mode, output, args.check_only)
    except (OSError, RuntimeError) as error:
        print(f"构建验证失败：{error}" if isinstance(error, RuntimeError) else "构建验证失败：无法读取或写入构建文件", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
