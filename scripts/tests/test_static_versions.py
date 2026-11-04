#!/usr/bin/env python3
"""Exercise cache invalidation in isolated Git repositories, without touching the app."""

from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


SCRIPTS = Path(__file__).resolve().parents[1]


class StaticVersionsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="emie-static-versions-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / "scripts").mkdir()
        for name in ("update-static-versions.sh", "update-static-versions.py"):
            shutil.copyfile(SCRIPTS / name, self.root / "scripts" / name)
        self.js = self.root / "src/main/resources/static/js"
        self.css = self.root / "src/main/resources/static/css"
        self.js.mkdir(parents=True)
        self.css.mkdir()
        self.bootstrap = self.js / "bootstrap.js"
        self.index = self.js.parent / "index.html"
        self.bootstrap.write_text("import './a.js?v=1';\n")
        (self.js / "a.js").write_text("const value = 1;\n")
        (self.css / "app.css").write_text("body { color: black; }\n")
        self.index.write_text('<link rel="stylesheet" href="/css/app.css?v=3">\n'
                              '<script type="module" src="/js/bootstrap.js?v=10"></script>\n')
        self.git("init", "-q")
        self.git("config", "user.email", "fixture@example.invalid")
        self.git("config", "user.name", "Fixture")
        self.git("add", "src")
        self.git("commit", "-qm", "baseline")

    def git(self, *args):
        return subprocess.run(["git", *args], cwd=self.root, check=True,
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout

    def run_update(self, *args):
        result = subprocess.run(["bash", "scripts/update-static-versions.sh", *args],
                                cwd=self.root, check=True, capture_output=True, text=True)
        return result.stdout

    def test_clean_and_repeated_updates_do_not_rewrite_sources(self):
        before = (self.bootstrap.stat().st_mtime_ns, self.index.stat().st_mtime_ns)
        self.assertEqual("cache_versions=unchanged\n", self.run_update())
        self.assertEqual("cache_versions=unchanged\n", self.run_update("a.js"))
        self.assertEqual(before, (self.bootstrap.stat().st_mtime_ns, self.index.stat().st_mtime_ns))

    def test_content_changes_bump_once_and_next_edit_bumps_again(self):
        asset = self.js / "a.js"
        asset.write_text("const value = 2;\n")
        output = self.run_update()
        self.assertIn("cache_bump=a.js v1->v2", output)
        self.assertIn("bootstrap.js?v=11", self.index.read_text())
        self.assertEqual("cache_versions=unchanged\n", self.run_update())
        asset.write_text("const value = 3;\n")
        self.assertIn("cache_bump=a.js v2->v3", self.run_update())
        self.assertIn("bootstrap.js?v=12", self.index.read_text())

    def test_staged_changes_and_new_modules_are_included(self):
        (self.js / "a.js").write_text("const value = 2;\n")
        (self.js / "new.js").write_text("const added = true;\n")
        self.bootstrap.write_text(self.bootstrap.read_text() + "import './new.js?v=1';\n")
        self.git("add", "src")
        output = self.run_update()
        self.assertIn("cache_bump=a.js v1->v2", output)
        self.assertIn("./new.js?v=1", self.bootstrap.read_text())
        self.assertIn("bootstrap.js?v=11", self.index.read_text())
        self.assertEqual("cache_versions=unchanged\n", self.run_update())

    def test_css_change_is_detected_even_with_javascript_hint(self):
        (self.css / "app.css").write_text("body { color: green; }\n")
        output = self.run_update("a.js")
        self.assertIn("cache_bump=app.css v3->v4", output)
        self.assertIn("app.css?v=4", self.index.read_text())
        self.assertIn("bootstrap.js?v=11", self.index.read_text())
        self.assertEqual("cache_versions=unchanged\n", self.run_update())

    def test_bootstrap_logic_edit_invalidates_entry(self):
        self.bootstrap.write_text(self.bootstrap.read_text() + "console.log('ready');\n")
        self.assertIn("cache_bump=bootstrap.js v10->v11", self.run_update())
        self.assertEqual("cache_versions=unchanged\n", self.run_update())

    def test_manual_module_bump_still_invalidates_entry(self):
        (self.js / "a.js").write_text("const value = 2;\n")
        self.bootstrap.write_text(self.bootstrap.read_text().replace("a.js?v=1", "a.js?v=5"))
        output = self.run_update()
        self.assertNotIn("cache_bump=a.js", output)
        self.assertIn("cache_bump=bootstrap.js v10->v11", output)

    def test_manual_versions_are_respected_and_recorded(self):
        self.run_update()
        (self.js / "a.js").write_text("const value = 2;\n")
        self.bootstrap.write_text(self.bootstrap.read_text().replace("a.js?v=1", "a.js?v=5"))
        self.index.write_text(self.index.read_text().replace("bootstrap.js?v=10", "bootstrap.js?v=19"))
        self.assertEqual("cache_versions=unchanged\n", self.run_update())
        (self.js / "a.js").write_text("const value = 3;\n")
        self.assertIn("cache_bump=a.js v5->v6", self.run_update())
        self.assertIn("bootstrap.js?v=20", self.index.read_text())

    def test_committed_changes_since_last_run_are_detected(self):
        self.run_update()
        (self.js / "a.js").write_text("const value = 2;\n")
        self.git("add", "src")
        self.git("commit", "-qm", "source changed")
        self.assertIn("cache_bump=a.js v1->v2", self.run_update())

    def test_corrupt_manifest_falls_back_to_git(self):
        self.run_update()
        (self.root / ".codex-build/static-versions.json").write_text("{bad json")
        (self.js / "a.js").write_text("const value = 2;\n")
        self.assertIn("cache_bump=a.js v1->v2", self.run_update())


if __name__ == "__main__":
    unittest.main()
