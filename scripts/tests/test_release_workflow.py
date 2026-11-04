"""Exercise release orchestration with isolated files and simulated network commands."""

import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import textwrap
import unittest


REPOSITORY = Path(__file__).resolve().parents[2]
TARGET_SHA = "a" * 40
ARTIFACT = b"verified full-test artifact\n"


FAKE_COMMAND = r'''#!/usr/bin/env python3
import hashlib
import json
import os
from pathlib import Path
import sys

fixture = Path(os.environ["RELEASE_FIXTURE"])
state_path = fixture / "state.json"
state = json.loads(state_path.read_text())
name = Path(sys.argv[0]).name
args = sys.argv[1:]

def save():
    state_path.write_text(json.dumps(state))

def event(kind, **values):
    with (fixture / "events.jsonl").open("a") as stream:
        stream.write(json.dumps({"kind": kind, **values}) + "\n")

def fail(message):
    print(message, file=sys.stderr)
    sys.exit(60)

if name == "sshpass":
    if len(args) < 2 or args[0] != "-e" or args[1] not in {"ssh", "scp"}:
        fail("unexpected sshpass invocation")
    os.execv(str(fixture / "bin" / args[1]), args[1:])

event(name, args=args)
if name == "git":
    if args == ["branch", "--show-current"]:
        print("master")
    elif args == ["status", "--porcelain"]:
        if state.get("dirty"):
            print(" M src/changed.java")
    elif args == ["fetch", "emie"]:
        pass
    elif args == ["rev-parse", "HEAD"]:
        print(state.get("head", "a" * 40))
    elif args == ["ls-remote", "emie", "refs/heads/master"]:
        print("a" * 40 + "\trefs/heads/master")
    else:
        fail("unexpected git invocation")
elif name == "ssh":
    command = args[-1]
    if command.startswith("test -f ") and "/tmp/emie-public-config.json" in command:
        print(state.get("current", "b" * 40))
    elif command.startswith("sed -n ") and "/tmp/emie-public-config.json" in command:
        print("1.18.1")
    elif "docker inspect emie-app" in command:
        pass
    elif command.startswith("mkdir -p "):
        pass
    elif command == "command -v rsync >/dev/null 2>&1":
        sys.exit(1 if state.get("use_scp") else 0)
    elif "cp --reflink=auto" in command:
        pass
    elif command.startswith("sha256sum "):
        print("0" * 64 if state.get("checksum_mismatch") else state["uploaded_sha"])
    elif "sudo -S -p '' env DEPLOY_DIR=" in command:
        event("activation", command=command)
        if state.get("activation_fail"):
            sys.exit(31)
        state["current"] = "a" * 40
        state["activated"] = True
        save()
    elif command.startswith("cat ") and command.endswith("/release-sha.txt'"):
        print(state.get("current", "b" * 40))
    else:
        fail("unexpected ssh command")
elif name in {"rsync", "scp"}:
    source = Path(args[-2])
    if not source.is_file():
        fail("upload source is missing")
    content = source.read_bytes()
    event("upload", transport=name, source=str(source), destination=args[-1],
          content=content.hex(), sha256=hashlib.sha256(content).hexdigest())
    if source.name == "release-production-remote.sh":
        if state.get("replace_target_during_upload"):
            target = fixture / "workspace with spaces/target/design-pm-1.0.0.jar"
            target.write_bytes(b"concurrent build overwrote shared target\n")
    elif source.suffix == ".jar":
        if state.get("upload_fail"):
            sys.exit(23)
        state["uploaded_sha"] = hashlib.sha256(content).hexdigest()
        save()
    else:
        fail("unexpected upload source")
elif name == "curl":
    if not args[-1].startswith("https://production.fixture.invalid/"):
        fail("unexpected curl target")
    if "/api/admin/public-config?" in args[-1]:
        print('{"system.version":"1.18.2"}')
    else:
        print("200")
else:
    fail("unexpected command")
'''


FAKE_BUILD = r'''
import argparse
import json
import os
from pathlib import Path
import sys

parser = argparse.ArgumentParser()
parser.add_argument("--source-root", required=True, type=Path)
parser.add_argument("--mode", required=True)
parser.add_argument("--output", required=True, type=Path)
args = parser.parse_args()
fixture = Path(os.environ["RELEASE_FIXTURE"])
state_path = fixture / "state.json"
state = json.loads(state_path.read_text())
with (fixture / "events.jsonl").open("a") as stream:
    stream.write(json.dumps({"kind": "build", "mode": args.mode,
                             "root": str(args.source_root), "output": str(args.output)}) + "\n")
args.output.write_bytes(b"verified full-test artifact\n")
if state.get("build_fail"):
    sys.exit(19)
if state.get("missing_artifact"):
    args.output.unlink()
if state.get("change_source_during_build"):
    state["dirty"] = True
if state.get("change_head_during_build"):
    state["head"] = "c" * 40
state_path.write_text(json.dumps(state))
'''


class ReleaseWorkflowTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="emie-release-fixture-")
        self.addCleanup(self.directory.cleanup)
        self.fixture = Path(self.directory.name)
        self.root = self.fixture / "workspace with spaces"
        scripts = self.root / "scripts"
        scripts.mkdir(parents=True)
        shutil.copyfile(REPOSITORY / "scripts/release-production.sh", scripts / "release-production.sh")
        (scripts / "release-production-remote.sh").write_text("fixture remote helper\n")
        (scripts / "verified-build.py").write_text(textwrap.dedent(FAKE_BUILD))
        (self.root / "target").mkdir()
        (self.root / "target/design-pm-1.0.0.jar").write_bytes(b"unverified shared target\n")
        self.temporary_artifacts = self.fixture / "temporary artifacts"
        self.temporary_artifacts.mkdir()
        binary_directory = self.fixture / "bin"
        binary_directory.mkdir()
        command = binary_directory / "fake-command"
        command.write_text(FAKE_COMMAND)
        command.chmod(0o755)
        for name in ("git", "ssh", "sshpass", "rsync", "scp", "curl"):
            (binary_directory / name).symlink_to(command)
        self.state = {}
        self.environment = {
            "PATH": str(binary_directory) + os.pathsep + os.environ.get("PATH", os.defpath),
            "RELEASE_FIXTURE": str(self.fixture),
            "TMPDIR": str(self.temporary_artifacts),
            "PRODUCTION_ENV_FILE": str(self.fixture / "absent-production.env"),
            "SERVER_ROLE": "production-local",
            "SERVER_HOST": "production.fixture.invalid",
            "SERVER_USER": "fixture",
            "SERVER_PORT": "22",
            "SERVER_PASSWORD": "fixture-password",
            "SERVER_PUBLIC_URL": "https://production.fixture.invalid",
            "SERVER_DEPLOY_DIR": "/fixture/deploy",
        }

    def run_release(self, *arguments):
        (self.fixture / "state.json").write_text(json.dumps(self.state))
        return subprocess.run(
            ["bash", str(self.root / "scripts/release-production.sh"), *arguments],
            env=self.environment,
            cwd=self.fixture,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            timeout=15,
        )

    def events(self, kind):
        path = self.fixture / "events.jsonl"
        events = [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []
        return [event for event in events if event["kind"] == kind]

    def assert_cleaned(self):
        self.assertEqual([], list(self.temporary_artifacts.iterdir()))
        for event in self.events("build"):
            self.assertFalse(Path(event["output"]).exists())

    def test_full_build_uploads_private_copy_even_if_shared_target_changes(self):
        self.state["replace_target_during_upload"] = True
        result = self.run_release()

        self.assertEqual(0, result.returncode, result.stdout)
        self.assertEqual(1, len(self.events("build")))
        build = self.events("build")[0]
        self.assertEqual("full", build["mode"])
        self.assertEqual(self.root.resolve(), Path(build["root"]).resolve())
        artifact = Path(build["output"])
        self.assertTrue(artifact.resolve().is_relative_to(self.temporary_artifacts.resolve()))
        self.assertEqual("app.jar", artifact.name)
        upload = [event for event in self.events("upload") if event["source"].endswith(".jar")]
        self.assertEqual(1, len(upload))
        self.assertEqual(str(artifact), upload[0]["source"])
        self.assertEqual(ARTIFACT.hex(), upload[0]["content"])
        self.assertEqual(hashlib.sha256(ARTIFACT).hexdigest(), upload[0]["sha256"])
        self.assertIn("app-" + TARGET_SHA + ".jar", upload[0]["destination"])
        self.assertEqual(1, len(self.events("activation")))
        self.assert_cleaned()

    def test_build_failure_blocks_upload_and_cleans_partial_artifact(self):
        self.state["build_fail"] = True
        result = self.run_release()

        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertEqual([], self.events("upload"))
        self.assertEqual([], self.events("activation"))
        self.assert_cleaned()

    def test_missing_output_blocks_upload(self):
        self.state["missing_artifact"] = True
        result = self.run_release()

        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertEqual([], self.events("upload"))
        self.assertEqual([], self.events("activation"))
        self.assert_cleaned()

    def test_source_or_head_change_during_build_blocks_upload(self):
        for change in ("change_source_during_build", "change_head_during_build"):
            with self.subTest(change=change):
                self.state = {change: True}
                (self.fixture / "events.jsonl").unlink(missing_ok=True)
                result = self.run_release()

                self.assertNotEqual(0, result.returncode, result.stdout)
                self.assertEqual([], self.events("upload"))
                self.assertEqual([], self.events("activation"))
                self.assert_cleaned()

    def test_upload_failure_or_checksum_mismatch_blocks_activation(self):
        for failure in ("upload_fail", "checksum_mismatch"):
            with self.subTest(failure=failure):
                self.state = {failure: True}
                (self.fixture / "events.jsonl").unlink(missing_ok=True)
                result = self.run_release()

                self.assertNotEqual(0, result.returncode, result.stdout)
                self.assertEqual([], self.events("activation"))
                self.assert_cleaned()

    def test_activation_failure_cleans_artifact(self):
        self.state["activation_fail"] = True
        result = self.run_release()

        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertEqual(1, len(self.events("activation")))
        self.assert_cleaned()

    def test_scp_fallback_uses_the_same_verified_copy(self):
        self.state["use_scp"] = True
        result = self.run_release()

        self.assertEqual(0, result.returncode, result.stdout)
        uploads = self.events("upload")
        self.assertEqual(2, len(uploads))
        self.assertTrue(all(event["transport"] == "scp" for event in uploads))
        self.assertEqual(ARTIFACT.hex(), uploads[-1]["content"])
        self.assertEqual(self.events("build")[0]["output"], uploads[-1]["source"])
        self.assert_cleaned()

    def test_preflight_does_not_build_or_upload(self):
        result = self.run_release("--preflight-only")

        self.assertEqual(0, result.returncode, result.stdout)
        self.assertEqual([], self.events("build"))
        self.assertEqual([], self.events("upload"))
        self.assertEqual([], self.events("activation"))
        self.assert_cleaned()

    def test_already_running_target_skips_build_and_upload(self):
        self.state["current"] = TARGET_SHA
        result = self.run_release()

        self.assertEqual(0, result.returncode, result.stdout)
        self.assertEqual([], self.events("build"))
        self.assertEqual([], self.events("upload"))
        self.assertEqual([], self.events("activation"))
        self.assert_cleaned()


if __name__ == "__main__":
    unittest.main()
