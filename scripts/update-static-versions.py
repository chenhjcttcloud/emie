#!/usr/bin/env python3
"""Refresh numeric cache versions once per actual static resource change."""

import fcntl
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile


JS_RE = re.compile(r"\./(?P<asset>[^'\"?\s]+\.js)\?v=(?P<version>\d+)")
CSS_RE = re.compile(r"/css/(?P<asset>[^'\"?\s]+\.css)\?v=(?P<version>\d+)")
ENTRY_RE = re.compile(r"/js/bootstrap\.js\?v=(?P<version>\d+)")
BOOTSTRAP = "src/main/resources/static/js/bootstrap.js"
INDEX = "src/main/resources/static/index.html"


def digest(content):
    return hashlib.sha256(content).hexdigest()


def git_content(root, path):
    result = subprocess.run(["git", "show", f"HEAD:{path}"], cwd=root,
                            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    return result.stdout if result.returncode == 0 else None


def references(bootstrap, index):
    refs = {}
    for pattern, text, directory in ((JS_RE, bootstrap, "js"), (CSS_RE, index, "css")):
        for match in pattern.finditer(text):
            relative = f"src/main/resources/static/{directory}/{match['asset']}"
            refs[relative] = int(match['version'])
    return refs


def baseline(root):
    bootstrap = git_content(root, BOOTSTRAP)
    index = git_content(root, INDEX)
    bootstrap_text = (bootstrap or b"").decode("utf-8")
    index_text = (index or b"").decode("utf-8")
    assets = {}
    for path, version in references(bootstrap_text, index_text).items():
        content = git_content(root, path)
        assets[path] = {"sha256": digest(content) if content is not None else None,
                        "version": version}
    entry = ENTRY_RE.search(index_text)
    return {"schema": 1, "assets": assets, "bootstrap": {
        "sha256": digest(bootstrap) if bootstrap is not None else None,
        "version": int(entry['version']) if entry else 0}}


def load_state(root, path):
    try:
        state = json.loads(path.read_text())
        if state.get("schema") != 1 or not isinstance(state.get("assets"), dict):
            raise ValueError("unsupported cache state")
        entries = [state["bootstrap"], *state["assets"].values()]
        for entry in entries:
            if not isinstance(entry.get("version"), int):
                raise ValueError("invalid cache version")
            value = entry.get("sha256")
            if value is not None and not re.fullmatch(r"[0-9a-f]{64}", value):
                raise ValueError("invalid resource digest")
        return state
    except (OSError, ValueError, KeyError, TypeError, AttributeError):
        return baseline(root)


def replace_version(text, pattern, asset, version):
    def replace(match):
        if asset is None or match['asset'] == asset:
            offset = match.start('version') - match.start()
            end = match.end('version') - match.start()
            return match[0][:offset] + str(version) + match[0][end:]
        return match[0]
    return pattern.sub(replace, text)


def update(root):
    cache = root / ".codex-build"
    cache.mkdir(exist_ok=True)
    state_path = cache / "static-versions.json"
    with (cache / "static-versions.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        previous = load_state(root, state_path)
        bootstrap_path, index_path = root / BOOTSTRAP, root / INDEX
        bootstrap = original_bootstrap = bootstrap_path.read_text()
        index = original_index = index_path.read_text()
        refs = references(bootstrap, index)
        assets = {}
        changed = False
        for relative, version in refs.items():
            path = root / relative
            if not path.is_file():
                raise ValueError(f"静态资源引用不存在: {relative}")
            sha = digest(path.read_bytes())
            old = previous["assets"].get(relative, {})
            old_version = old.get("version", 0)
            if sha != old.get("sha256") and version <= old_version:
                next_version = max(version, old_version) + 1
                pattern = JS_RE if path.suffix == ".js" else CSS_RE
                if path.suffix == ".js":
                    bootstrap = replace_version(bootstrap, pattern,
                                                relative.split("/static/js/", 1)[1], next_version)
                else:
                    index = replace_version(index, pattern,
                                            relative.split("/static/css/", 1)[1], next_version)
                print(f"cache_bump={path.name} v{version}->v{next_version}")
                version = next_version
                changed = True
            assets[relative] = {"sha256": sha, "version": version}

        entry = ENTRY_RE.search(index)
        if not entry:
            raise ValueError("首页缺少 bootstrap 缓存版本")
        version = int(entry['version'])
        old_entry = previous["bootstrap"]
        # Hash the final import graph, so module version changes invalidate the entry too.
        bootstrap_sha = digest(bootstrap.encode("utf-8"))
        if (bootstrap_sha != old_entry["sha256"] or changed) and version <= old_entry["version"]:
            next_version = max(version, old_entry["version"]) + 1
            index = replace_version(index, ENTRY_RE, None, next_version)
            print(f"cache_bump=bootstrap.js v{version}->v{next_version}")
            version = next_version
            changed = True
        if bootstrap != original_bootstrap:
            bootstrap_path.write_text(bootstrap)
        if index != original_index:
            index_path.write_text(index)
        state = {"schema": 1, "assets": assets,
                 "bootstrap": {"sha256": bootstrap_sha, "version": version}}
        encoded = json.dumps(state, indent=2, sort_keys=True) + "\n"
        if not state_path.exists() or state_path.read_text() != encoded:
            with tempfile.NamedTemporaryFile(mode="w", dir=cache, delete=False) as output:
                output.write(encoded)
                temporary = Path(output.name)
            temporary.replace(state_path)
        if not changed:
            print("cache_versions=unchanged")


if __name__ == "__main__":
    # Existing callers may still supply module names. Check all referenced resources
    # together so a caller's hints cannot miss staged JavaScript or independent CSS.
    try:
        update(Path(__file__).resolve().parent.parent)
    except (OSError, ValueError) as error:
        print(f"静态资源版本更新失败: {error}", file=sys.stderr)
        raise SystemExit(1)
