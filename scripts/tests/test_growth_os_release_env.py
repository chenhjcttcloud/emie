"""Exercise the Growth OS production loader using only owned private fixtures."""

import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/release-production-remote.sh"
TOKEN = "fixture-growth-release-token==with=equals-123456789"
ORIGINAL = {
    "KEEP_EXISTING": "preserved",
    "GROWTH_OS_BASE_URL": "https://old.fixture.invalid",
    "GROWTH_OS_SCORE_TOKEN": "old-fixture-token-for-preservation-123456789",
    "GROWTH_OS_ALLOW_LOCAL_HTTP": "true",
}


class GrowthOsReleaseEnvironmentTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="emie-growth-release-owned-fixture-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.root.chmod(0o700)
        self.file = self.root / ".growth-os.env"
        self.calls = self.root / "setter-calls"
        text = SCRIPT.read_text()
        start = text.index("load_growth_os_env() {")
        end = text.index("\n}\n", start) + len("\n}")
        self.function = text[start:end]

    def configuration(self, content=None, *, mode=0o600):
        if content is None:
            content = "GROWTH_OS_BASE_URL=https://growth.fixture.invalid\nGROWTH_OS_SCORE_TOKEN=" + TOKEN + "\n"
        self.file.write_bytes(content.encode() if isinstance(content, str) else content)
        self.file.chmod(mode)

    def run_loader(self, *, owner="0"):
        # stat is simulated only because host tests need not run as root or use
        # GNU stat. File size and mode still come from the actual owned fixture.
        fake_stat = """
stat() {
  "$TEST_PYTHON" -c '
import os,stat,sys
option,field,path=sys.argv[1:]
if option != "-c": raise SystemExit(2)
value=os.stat(path)
print({"%u":os.environ["FIXTURE_OWNER"], "%a":format(stat.S_IMODE(value.st_mode),"o"), "%s":value.st_size}[field])
' "$@"
}
"""
        fake_setter = """
set_env_value() {
  local key="$1" value="$2" index
  printf '%s=%s\\n' "$key" "$value" >> "$CALLS_FILE"
  for index in "${!runtime_env[@]}"; do
    if [[ "${runtime_env[$index]}" == "$key="* ]]; then
      runtime_env[$index]="$key=$value"
      return
    fi
  done
  runtime_env+=("$key=$value")
}
"""
        program = "set -Eeuo pipefail\numask 077\n"
        program += "die() { printf 'loader_rejected=%s\\n' \"$1\" >&2; exit 2; }\n"
        program += fake_stat + fake_setter + self.function + "\n"
        program += "runtime_env=(\"KEEP_EXISTING=preserved\" \"GROWTH_OS_BASE_URL=https://old.fixture.invalid\" "
        program += "\"GROWTH_OS_SCORE_TOKEN=old-fixture-token-for-preservation-123456789\" \"GROWTH_OS_ALLOW_LOCAL_HTTP=true\")\n"
        program += "load_growth_os_env\nprintf '%s\\0' \"${runtime_env[@]}\"\n"
        environment = {
            **os.environ,
            "DEPLOY_DIR": str(self.root),
            "CALLS_FILE": str(self.calls),
            "TEST_PYTHON": sys.executable,
            "FIXTURE_OWNER": owner,
        }
        return subprocess.run(["bash"], input=program.encode(), env=environment, capture_output=True, timeout=10)

    def values(self, result):
        return dict(item.split("=", 1) for item in result.stdout.decode().split("\0") if item)

    def assert_rejected(self, result):
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.calls.exists(), "invalid files must not partially change candidate environment")
        self.assertNotIn(TOKEN.encode(), result.stdout + result.stderr)

    def test_valid_file_updates_only_candidate_values_preserving_equals_in_token(self):
        self.configuration()
        result = self.run_loader()
        self.assertEqual(0, result.returncode, result.stderr.decode())
        self.assertEqual({**ORIGINAL, "GROWTH_OS_BASE_URL": "https://growth.fixture.invalid",
                          "GROWTH_OS_SCORE_TOKEN": TOKEN, "GROWTH_OS_ALLOW_LOCAL_HTTP": "false"},
                         self.values(result))
        self.assertEqual(0o600, stat.S_IMODE(self.calls.stat().st_mode))
        self.assertEqual(3, len(self.calls.read_text().splitlines()))

    def test_absent_file_preserves_existing_candidate_environment(self):
        result = self.run_loader()
        self.assertEqual(0, result.returncode, result.stderr.decode())
        self.assertEqual(ORIGINAL, self.values(result))
        self.assertFalse(self.calls.exists())

    def test_shell_expansions_are_data_and_are_never_executed(self):
        marker = self.root / "must-not-exist"
        for malicious in (
                "GROWTH_OS_BASE_URL=$(touch " + str(marker) + ")\nGROWTH_OS_SCORE_TOKEN=" + TOKEN,
                "GROWTH_OS_BASE_URL=https://growth.fixture.invalid\nGROWTH_OS_SCORE_TOKEN=$(touch " + str(marker) + ")",
                "source=" + str(marker) + "\nGROWTH_OS_BASE_URL=https://growth.fixture.invalid\nGROWTH_OS_SCORE_TOKEN=" + TOKEN):
            with self.subTest(payload=malicious.split("=", 1)[0]):
                self.configuration(malicious)
                self.assert_rejected(self.run_loader())
                self.assertFalse(marker.exists())

    def test_unknown_duplicate_missing_and_non_key_value_lines_are_rejected(self):
        valid = "GROWTH_OS_BASE_URL=https://growth.fixture.invalid\nGROWTH_OS_SCORE_TOKEN=" + TOKEN
        for content in (valid + "\nOTHER=value", valid + "\nGROWTH_OS_SCORE_TOKEN=" + TOKEN,
                        valid + "\nGROWTH_OS_BASE_URL=https://other.fixture.invalid",
                        "GROWTH_OS_BASE_URL=https://growth.fixture.invalid", "GROWTH_OS_SCORE_TOKEN=" + TOKEN,
                        valid + "\nnot-an-assignment", valid + "\nGROWTH_OS_ALLOW_LOCAL_HTTP=true"):
            with self.subTest(content=content.splitlines()[-1].split("=", 1)[0]):
                self.configuration(content)
                self.assert_rejected(self.run_loader())

    def test_permissions_owner_symlink_and_directory_are_rejected(self):
        self.configuration(mode=0o640)
        self.assert_rejected(self.run_loader())
        self.configuration()
        self.assert_rejected(self.run_loader(owner="1001"))
        target = self.root / "owned-config-target"
        self.file.rename(target)
        self.file.symlink_to(target)
        self.assert_rejected(self.run_loader())
        self.file.rename(self.root / "owned-config-symlink")
        self.file.mkdir()
        self.assert_rejected(self.run_loader())

    def test_cr_nul_control_characters_and_oversized_files_are_rejected(self):
        valid = ("GROWTH_OS_BASE_URL=https://growth.fixture.invalid\nGROWTH_OS_SCORE_TOKEN=" + TOKEN).encode()
        for content in (valid + b"\r\n", valid + b"\x00", valid + b"\x01", b"#bad\tcomment\n" + valid,
                        b"#" + b"x" * 16384 + b"\n" + valid):
            with self.subTest(size=len(content)):
                self.configuration(content)
                self.assert_rejected(self.run_loader())

    def test_non_https_userinfo_path_query_fragment_and_invalid_ports_are_rejected(self):
        for address in ("http://growth.fixture.invalid", "https://user@growth.fixture.invalid",
                        "https://growth.fixture.invalid/path", "https://growth.fixture.invalid/?query=1",
                        "https://growth.fixture.invalid/#fragment", "https://growth.fixture.invalid:0",
                        "https://growth.fixture.invalid:65536", "https://growth..fixture.invalid",
                        "https://-growth.fixture.invalid", "https://growth.fixture.invalid:abc"):
            with self.subTest(address=address):
                self.configuration("GROWTH_OS_BASE_URL=" + address + "\nGROWTH_OS_SCORE_TOKEN=" + TOKEN)
                self.assert_rejected(self.run_loader())

    def test_tokens_must_be_printable_ascii_between_32_and_4096_characters(self):
        for token in ("x" * 31, "x" * 4097, "x" * 31 + " ", "x" * 31 + "中", "x" * 31 + "\x7f", ""):
            with self.subTest(length=len(token)):
                self.configuration("GROWTH_OS_BASE_URL=https://growth.fixture.invalid\nGROWTH_OS_SCORE_TOKEN=" + token)
                self.assert_rejected(self.run_loader())

    def test_https_root_with_port_and_trailing_slash_is_accepted(self):
        self.configuration("GROWTH_OS_BASE_URL=https://growth.fixture.invalid:0443/\nGROWTH_OS_SCORE_TOKEN=" + TOKEN)
        result = self.run_loader()
        self.assertEqual(0, result.returncode, result.stderr.decode())
        self.assertEqual("https://growth.fixture.invalid:0443/", self.values(result)["GROWTH_OS_BASE_URL"])


if __name__ == "__main__":
    unittest.main()
