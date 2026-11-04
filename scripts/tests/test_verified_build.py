#!/usr/bin/env python3
"""Exercise verified-build with a fake Maven entry point and isolated files."""

import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


HELPER = Path(__file__).resolve().parents[1] / "verified-build.py"
WRAPPER = r'''#!/usr/bin/env python3
import json, os, pathlib, shutil, sys, time
root = pathlib.Path.cwd()
control = root / '.codex-build'
if sys.argv[1:] == ['--version']:
    print('Apache Maven ' + os.environ.get('FAKE_MAVEN_VERSION', '3.9.6'))
    print('Java version: 21.0.12')
    sys.exit(0)
with (control / 'calls.jsonl').open('a') as stream:
    stream.write(json.dumps(sys.argv[1:]) + '\n')
if os.environ.get('FAKE_SLEEP'):
    time.sleep(float(os.environ['FAKE_SLEEP']))
target = root / 'target'
shutil.rmtree(target, ignore_errors=True)
target.mkdir()
if os.environ.get('FAKE_FAIL'):
    print('private_failure_value=' + os.environ.get('MAVEN_OPTS', ''))
    sys.exit(1)
(target / 'design-pm-1.0.0.jar').write_bytes(b'jar:' + (root / 'src/main/java/Example.java').read_bytes())
if os.environ.get('FAKE_MUTATE_SOURCE'):
    (root / 'src/main/java/Example.java').write_text('modified during build')
if '-DskipTests' not in sys.argv:
    reports = target / 'surefire-reports'
    reports.mkdir()
    mode = os.environ.get('FAKE_REPORT', 'valid')
    documents = {
        'valid': '<testsuite tests="2" failures="0" errors="0" skipped="1"><testcase/><testcase><skipped/></testcase></testsuite>',
        'failure': '<testsuite tests="1" failures="1" errors="0" skipped="0"><testcase><failure/></testcase></testsuite>',
        'error': '<testsuite tests="1" failures="0" errors="1" skipped="0"><testcase><error/></testcase></testsuite>',
        'skipped': '<testsuite tests="1" failures="0" errors="0" skipped="1"><testcase><skipped/></testcase></testsuite>',
        'zero': '<testsuite tests="0" failures="0" errors="0" skipped="0"/>',
        'inconsistent': '<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase><failure/></testcase></testsuite>',
        'invalid': '<testsuite',
    }
    if mode != 'missing':
        report = reports / 'TEST-Example.xml'
        report.write_text(documents.get(mode, documents['valid']))
        if mode == 'stale':
            os.utime(report, (1, 1))
'''


class VerifiedBuildTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name) / "source with spaces"
        (self.root / "scripts").mkdir(parents=True)
        (self.root / "src/main/java").mkdir(parents=True)
        (self.root / "pom.xml").write_text("<project/>")
        (self.root / "mvnw").write_text("fixture Maven wrapper")
        self.source = self.root / "src/main/java/Example.java"
        self.source.write_text("class Example {}")
        wrapper = self.root / "scripts/mvnw-java21.sh"
        wrapper.write_text(WRAPPER)
        wrapper.chmod(0o755)
        self.environment = dict(os.environ)
        self.environment.update(MAVEN_USER_HOME=str(self.root / ".m2"))

    def run_build(self, mode="package", output=None, extra=None, expected=0, check_only=False):
        command = [sys.executable, str(HELPER), "--source-root", str(self.root), "--mode", mode]
        if output is not None:
            command.extend(["--output", str(output)])
        if check_only:
            command.append("--check-only")
        result = subprocess.run(command, env={**self.environment, **(extra or {})}, capture_output=True, text=True, timeout=20)
        self.assertEqual(expected, result.returncode, result.stderr)
        values = dict(line.split("=", 1) for line in result.stdout.splitlines())
        return result, values

    def calls(self):
        path = self.root / ".codex-build/calls.jsonl"
        return [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []

    def receipt(self):
        return self.root / ".codex-build/verified-build.json"

    def test_package_reuses_unchanged_artifact_and_copies_verified_jar(self):
        output = Path(self.temporary.name) / "copied artifact.jar"
        _, first = self.run_build(output=output)
        _, second = self.run_build(output=output)
        self.assertEqual("built", first["verified_build"])
        self.assertEqual("reused", second["verified_build"])
        self.assertEqual([["clean", "package", "-DskipTests", "-q"]], self.calls())
        self.assertEqual(second["jar_sha256"], hashlib.sha256(output.read_bytes()).hexdigest())
        self.assertEqual(str(output), second["jar_path"])

    def test_copy_replaces_destination_symlink_with_an_independent_artifact(self):
        self.run_build()
        output = Path(self.temporary.name) / "copied.jar"
        jar = self.root / "target/design-pm-1.0.0.jar"
        output.symlink_to(jar)
        self.run_build(output=output)
        self.assertFalse(output.is_symlink())
        copied = output.read_bytes()
        jar.write_bytes(b"later replacement")
        self.assertEqual(copied, output.read_bytes())

    def test_package_receipt_requires_full_build_then_full_receipt_is_preserved(self):
        self.run_build()
        _, full = self.run_build("full")
        receipt = self.receipt().read_bytes()
        _, package = self.run_build()
        _, repeated_full = self.run_build("full")
        self.assertEqual("full", full["build_mode"])
        self.assertEqual("full", package["build_mode"])
        self.assertEqual("reused", package["verified_build"])
        self.assertEqual("reused", repeated_full["verified_build"])
        self.assertEqual(receipt, self.receipt().read_bytes())
        self.assertEqual(["clean", "package"], self.calls()[-1])
        self.assertEqual(2, len(self.calls()))

    def test_source_resources_tests_scripts_and_optional_maven_inputs_invalidate(self):
        self.run_build()
        paths = ["src/main/java/Example.java", "src/main/resources/example.txt", "src/test/java/ExampleTest.java",
                 "scripts/release-fixture.sh", "scripts/modal-e2e/regression.mjs", ".mvn/maven.config",
                 "db/migrations/example.sql", "pom.xml", "mvnw"]
        for index, name in enumerate(paths, start=2):
            with self.subTest(input=name):
                path = self.root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("changed input")
                _, values = self.run_build()
                self.assertEqual("built", values["verified_build"])
                self.assertEqual(index, len(self.calls()))
        (self.root / "src/main/resources/example.txt").unlink()
        self.run_build()
        self.assertEqual(len(paths) + 2, len(self.calls()))

    def test_node_modules_cache_and_docs_do_not_invalidate(self):
        (self.root / "scripts/modal-e2e").mkdir()
        self.run_build("full")
        for name in ["scripts/node_modules/dependency.js", "scripts/__pycache__/cached.pyc", "CHANGELOG.md",
                     "scripts/modal-e2e/test-results/modal-e2e/screenshot.png",
                     "scripts/modal-e2e/test-results/modal-e2e/run-1-trace.zip",
                     "scripts/modal-e2e/test-results/modal-e2e/run-1-error.txt"]:
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("not a build input")
        _, values = self.run_build("full", check_only=True)
        self.assertEqual("reused", values["verified_build"])
        self.assertEqual(1, len(self.calls()))

    def test_environment_and_actual_maven_version_invalidate_without_leaking_values(self):
        self.run_build()
        secret = "-Dfixture.secret=private-build-value"
        result, values = self.run_build(extra={"MAVEN_OPTS": secret})
        self.assertEqual("built", values["verified_build"])
        self.assertNotIn(secret, result.stdout + result.stderr + self.receipt().read_text())
        _, values = self.run_build(extra={"MAVEN_OPTS": secret, "FAKE_MAVEN_VERSION": "3.9.9"})
        self.assertEqual("built", values["verified_build"])
        self.assertEqual(3, len(self.calls()))

    def test_user_maven_settings_changes_invalidate(self):
        self.run_build()
        settings = self.root / ".m2/settings.xml"
        settings.parent.mkdir()
        settings.write_text("<settings><profiles/></settings>")
        _, values = self.run_build()
        self.assertEqual("built", values["verified_build"])
        self.assertEqual(2, len(self.calls()))

    def test_check_only_never_builds_and_rejects_stale_receipts(self):
        self.run_build(check_only=True, expected=1)
        self.assertEqual([], self.calls())
        self.run_build()
        _, values = self.run_build(check_only=True)
        self.assertEqual("reused", values["verified_build"])
        self.run_build("full", check_only=True, expected=1)
        self.assertEqual(1, len(self.calls()))
        self.source.write_text("changed input")
        self.run_build(check_only=True, expected=1)
        self.assertEqual(1, len(self.calls()))

    def test_full_rejects_external_test_selection_and_skip_arguments(self):
        for name in ("MAVEN_ARGS", "MAVEN_OPTS", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"):
            for option in ("-Dtest=OnlyOneTest", "-DskipTests", "-Dmaven.test.skip=true", "-Dsurefire.excludes=**/*", '-D"test"="OnlyOneTest"'):
                with self.subTest(environment=name, option=option):
                    self.run_build("full", extra={name: option}, expected=1)
        self.assertEqual([], self.calls())
        self.assertFalse(self.receipt().exists())

    def test_full_rejects_project_test_selection_configuration(self):
        configuration = self.root / ".mvn/maven.config"
        configuration.parent.mkdir()
        configuration.write_text("--define\ntest=OnlyOneTest")
        self.run_build("full", expected=1)
        self.assertEqual([], self.calls())
        configuration.write_text("-DskipTests=false")
        self.run_build("full", expected=1)
        self.assertEqual([], self.calls())

    def test_full_rejects_test_selection_in_user_settings(self):
        settings = self.root / ".m2/settings.xml"
        settings.parent.mkdir()
        settings.write_text("<settings><profiles><profile><properties><test>OnlyOneTest</test></properties></profile></profiles></settings>")
        self.run_build("full", expected=1)
        self.assertEqual([], self.calls())

    def test_missing_malformed_and_mismatched_receipts_rebuild(self):
        self.run_build()
        self.receipt().unlink()
        self.run_build()
        self.receipt().write_text("{invalid")
        self.run_build()
        receipt = json.loads(self.receipt().read_text())
        receipt["command"] = ["clean", "package", "-DskipTests"]
        self.receipt().write_text(json.dumps(receipt))
        self.run_build()
        self.assertEqual(4, len(self.calls()))

    def test_changed_or_missing_jar_rebuilds(self):
        self.run_build()
        jar = self.root / "target/design-pm-1.0.0.jar"
        jar.write_bytes(b"unexpected artifact")
        self.run_build()
        jar.unlink()
        self.run_build()
        self.assertEqual(3, len(self.calls()))

    def test_full_requires_current_valid_reports_and_executed_tests(self):
        for report in ["missing", "invalid", "failure", "error", "zero", "skipped", "inconsistent", "stale"]:
            with self.subTest(report=report):
                self.run_build("full", extra={"FAKE_REPORT": report}, expected=1)
                self.assertFalse(self.receipt().exists())

    def test_changed_full_report_invalidates_full_receipt(self):
        self.run_build("full")
        (self.root / "target/surefire-reports/TEST-Example.xml").write_text("invalid XML")
        _, values = self.run_build("full")
        self.assertEqual("built", values["verified_build"])
        self.assertEqual(2, len(self.calls()))

    def test_build_failure_removes_previous_receipt_and_keeps_private_logs_private(self):
        self.run_build()
        self.source.write_text("changed input")
        result, _ = self.run_build(extra={"FAKE_FAIL": "1", "MAVEN_OPTS": "private-build-secret"}, expected=1)
        self.assertFalse(self.receipt().exists())
        self.assertNotIn("private-build-secret", result.stdout + result.stderr)
        self.assertEqual(0o600, (self.root / ".codex-build/verified-build.log").stat().st_mode & 0o777)

    def test_changes_during_build_are_rejected_without_copy_or_receipt(self):
        output = Path(self.temporary.name) / "should-not-exist.jar"
        self.run_build(output=output, extra={"FAKE_MUTATE_SOURCE": "1"}, expected=1)
        self.assertFalse(self.receipt().exists())
        self.assertFalse(output.exists())

    def test_concurrent_builds_share_one_artifact_and_copy_under_lock(self):
        command = [sys.executable, str(HELPER), "--source-root", str(self.root), "--mode", "full"]
        outputs = [Path(self.temporary.name) / f"copy-{index}.jar" for index in range(2)]
        processes = [subprocess.Popen(command + ["--output", str(output)], env={**self.environment, "FAKE_SLEEP": ".2"},
                                      stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True) for output in outputs]
        results = [process.communicate(timeout=20) for process in processes]
        for process, (stdout, stderr) in zip(processes, results):
            self.assertEqual(0, process.returncode, stderr)
        self.assertEqual(1, len(self.calls()))
        self.assertEqual(outputs[0].read_bytes(), outputs[1].read_bytes())
        modes = [dict(line.split("=", 1) for line in stdout.splitlines())["verified_build"] for stdout, _ in results]
        self.assertCountEqual(["built", "reused"], modes)

    def test_symlinked_source_is_rejected_but_excluded_dependency_symlink_is_safe(self):
        external = Path(self.temporary.name) / "external.java"
        external.write_text("external contents")
        link = self.root / "src/main/java/External.java"
        link.symlink_to(external)
        self.run_build(expected=1)
        self.assertEqual([], self.calls())
        link.unlink()
        (self.root / "scripts/node_modules").symlink_to(external)
        self.run_build()
        self.assertEqual(1, len(self.calls()))


if __name__ == "__main__":
    unittest.main()
