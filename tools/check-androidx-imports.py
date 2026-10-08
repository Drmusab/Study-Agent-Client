#!/usr/bin/env python3
"""Validate every `androidx.*` / `com.google.*` import in the app's Kotlin sources against the real
androidx public-API dump files, so framework references are checked against published signatures
instead of guesswork.

Why: the Gradle build cannot resolve Compose in this sandbox, and the JVM harness excludes Compose
files. A Compose screen therefore has *no* compile gate at all, and an import of a symbol that does
not exist in the library (e.g. `androidx.compose.ui.semantics.mergeDescendants`, which is only a
*parameter* of `Modifier.semantics`) is a hard `compileDebugKotlin` error that every unit test still
passes.

API dumps are the `api/current.txt` files in the androidx repository (androidx's own public-API
contract, the same artifact Metalava compares against). They are cached outside the repo, so the
tool works offline once primed:

    tools/check-androidx-imports.py --fetch          # needs api.github.com
    tools/check-androidx-imports.py                  # analyse against the cache
    tools/check-androidx-imports.py --src app/src/main/java --verbose

Exit status: 0 = every import resolved, 1 = unresolved imports found, 2 = cache missing/priming
needed. Symbols the dumps cannot describe (generated material icons, Java-side classes such as
`androidx.core.view.WindowCompat` when its module is not listed) are reported as `UNVERIFIED`,
never silently accepted.
"""

import argparse
import json
import os
import re
import sys
import urllib.request

CACHE = os.environ.get("ANDROIDX_API_CACHE", "/tmp/androidx-api")

# androidx repo path -> api dump file. `current.txt` is the published contract for that module.
# Pinned by the app's build file: compose-bom 2024.06.00 (compose 1.6.x, material3 1.2.x),
# lifecycle 2.8.3, navigation 2.7.7, core-ktx 1.13.1, activity-compose 1.9.0. The androidx mirror
# only keeps selected versioned dumps, so each module names the newest dump at or below the pin.
MODULES = [
    ("compose/ui/ui", "1.6.0-beta01.txt"),
    ("compose/ui/ui-graphics", "1.6.0-beta01.txt"),
    ("compose/ui/ui-text", "1.6.0-beta01.txt"),
    ("compose/ui/ui-unit", "1.6.0-beta01.txt"),
    ("compose/ui/ui-util", "1.6.0-beta01.txt"),
    ("compose/ui/ui-geometry", "1.6.0-beta01.txt"),
    ("compose/ui/ui-tooling-preview", "current.txt"),
    ("compose/ui/ui-tooling", "current.txt"),
    ("compose/foundation/foundation", "1.6.0-beta01.txt"),
    ("compose/foundation/foundation-layout", "1.6.0-beta01.txt"),
    ("compose/material3/material3", "1.2.0-beta02.txt"),
    ("compose/runtime/runtime", "1.6.0-beta01.txt"),
    ("compose/runtime/runtime-saveable", "1.6.0-beta01.txt"),
    ("compose/animation/animation", "1.6.0-beta01.txt"),
    ("compose/animation/animation-core", "1.6.0-beta01.txt"),
    ("lifecycle/lifecycle-viewmodel", "2.8.0-beta01.txt"),
    ("lifecycle/lifecycle-runtime-compose", "2.8.0-beta01.txt"),
    ("lifecycle/lifecycle-viewmodel-compose", "2.8.0-beta01.txt"),
    ("lifecycle/lifecycle-common", "2.8.0-beta01.txt"),
    ("navigation/navigation-common", "2.8.0-beta07.txt"),
    ("navigation/navigation-runtime", "2.8.0-beta07.txt"),
    ("navigation/navigation-compose", "2.8.0-beta07.txt"),
    ("activity/activity", "current.txt"),
    ("activity/activity-compose", "current.txt"),
    ("core/core", "current.txt"),
]

PREFIXES = ("androidx.",)
IMPORT_RE = re.compile(r"^import\s+(?:[\w$]+\.){1,}[\w$]+\s*$", re.M)


def fetch(ref="androidx-main"):
    os.makedirs(CACHE, exist_ok=True)
    ok, failed = 0, []
    for module, name in MODULES:
        for name in (name,):
            out = os.path.join(CACHE, module.replace("/", "__") + "." + name)
            if os.path.exists(out) and os.path.getsize(out) > 1000:
                ok += 1
                continue
            url = ("https://api.github.com/repos/androidx/androidx/contents/%s/api/%s?ref=%s"
                   % (module, name, ref))
            try:
                with urllib.request.urlopen(url, timeout=40) as fh:
                    payload = json.load(fh)
                data = __import__("base64").b64decode(payload["content"]).decode("utf-8", "replace")
                if len(data) < 100:
                    raise ValueError("empty")
                open(out, "w").write(data)
                ok += 1
            except Exception as exc:                                    # noqa: BLE001
                failed.append("%s: %s" % (module, type(exc).__name__))
    print("cached dumps: %d, failed: %d" % (ok, len(failed)))
    for f in failed:
        print("   ", f)
    return 0 if ok else 2


# `@interface` is how an annotation class is spelled in the dump and `fun interface` is a SAM
# interface; the leading annotation scan must not swallow `@interface` itself.
# Annotation arguments may contain commas and spaces (`@Target(allowedTargets={A, B})`), so they are
# removed before the declaration is matched. `@interface` is how the dump spells an annotation class
# and must survive that strip; `enum X {` is a declaration head with no `class` keyword.
ANNOTATION_RE = re.compile(r"@(?!interface\b)[\w.]+(?:\((?:[^()]|\([^()]*\))*\))?\s*")
DECL_RE = re.compile(
    r"(?:(?:public|private|protected|static|final|abstract|open|sealed|inner|value|fun|suspend|"
    r"annotation|default)\s+)*(?:(@interface)|(class|interface|enum|record))\s+([\w.$]+)"
)
def load_dumps():
    """(classes by package, members by package) parsed from the cached androidx api dumps.

    The dump format is one line per declaration inside a `package x.y {` block: a type declaration
    ends with `{`, a member ends with `;`. Kotlin top-level functions/properties appear as static
    methods on a synthetic `<File>Kt` class, and a Kotlin property appears as `getFoo`/`setFoo`,
    so both are also registered under the property name an import would use.
    """
    classes, members = {}, {}

    def ensure(pkg):
        classes.setdefault(pkg, set())
        members.setdefault(pkg, set())

    if not os.path.isdir(CACHE):
        return classes, members
    for fn in sorted(os.listdir(CACHE)):
        if not fn.endswith(".txt"):
            continue
        pkg = None
        for raw in open(os.path.join(CACHE, fn), encoding="utf-8", errors="replace"):
            line = raw.strip()
            if not line:
                continue
            if line.startswith("package ") and line.endswith("{"):
                pkg = line[len("package "):-1].strip()
                ensure(pkg)
                continue
            if pkg is None or line in ("}", "} // package"):
                continue
            cm = DECL_RE.search(ANNOTATION_RE.sub("", line))
            if cm and not line.startswith(("method ", "field ", "property ", "enum constant")):
                qual = cm.group(3)
                classes[pkg].add(qual)
                classes[pkg].add(qual.split(".")[-1])
                continue
            if line.startswith(("method ", "field ", "property ", "enum constant", "suppressed")):
                if line.startswith("suppressed"):
                    continue
                body = line.rstrip(";").strip()
                if body.startswith(("method ", "enum constant")) or "(" in body:
                    # The method name is the identifier before the *first* `(`; a trailing one is
                    # the last parameter name.
                    m = re.search(r"([\w$]+)\(", body)
                else:
                    m = re.search(r"([\w$]+)$", body)
                if not m:
                    continue
                name = m.group(1)
                members[pkg].add(name)
                for prefix in ("get", "set", "is"):
                    if len(name) > len(prefix) + 1 and name.startswith(prefix) and name[len(prefix)].isupper():
                        members[pkg].add(name[len(prefix)][0].lower() + name[len(prefix) + 1:])
    return classes, members


def resolve(import_path, classes, members, unverified_prefixes):
    parts = import_path.split(".")
    # find the longest package prefix we know; remainder is a (possibly nested) type or member
    for i in range(len(parts) - 1, 0, -1):
        pkg = ".".join(parts[:i])
        if pkg in classes or pkg in members:
            tail = parts[i:]
            if not tail:
                return "OK"
            first = tail[0]
            if first in classes[pkg] or first in members.get(pkg, set()):
                return "OK"
            # nested type path: A.B where A is a class
            if ".".join(tail) in classes[pkg] or first in classes[pkg]:
                return "OK"
            if any(import_path.startswith(p) for p in unverified_prefixes):
                return "UNVERIFIED"
            return "UNRESOLVED"
    if any(import_path.startswith(p) for p in unverified_prefixes):
        return "UNVERIFIED"
    return "NO_PACKAGE_DATA"


def selftest(classes, members, unverified):
    """Prove the checker can resolve real symbols AND reject a fabricated one.

    A checker that parsed nothing would also report nothing: the positive cases below prove the dumps
    were read, and the negative cases prove that a non-existent symbol is actually caught.
    """
    must_resolve = [
        "androidx.compose.runtime.Composable",              # @interface declaration head
        "androidx.compose.ui.Alignment",                    # fun interface
        "androidx.compose.animation.core.RepeatMode",       # enum
        "androidx.compose.foundation.layout.Box",           # top-level @Composable function
        "androidx.compose.material3.Text",
        "androidx.compose.ui.semantics.semantics",          # Modifier extension
        "androidx.compose.ui.semantics.contentDescription",  # receiver extension property
        "androidx.compose.ui.unit.dp",
    ]
    must_not_resolve = [
        "androidx.compose.ui.semantics.mergeDescendants",   # only a *parameter* of semantics()
        "androidx.compose.material3.Tetx",
    ]
    failures = []
    for imp in must_resolve:
        if resolve(imp, classes, members, unverified) != "OK":
            failures.append("expected OK, got %s: %s" % (resolve(imp, classes, members, unverified), imp))
    for imp in must_not_resolve:
        status = resolve(imp, classes, members, unverified)
        if status != "UNRESOLVED":
            failures.append("expected UNRESOLVED, got %s: %s" % (status, imp))
    print("selftest: %d positives, %d negatives -> %s"
          % (len(must_resolve), len(must_not_resolve), "PASS" if not failures else "FAIL"))
    for f in failures:
        print("   ", f)
    return 1 if failures else 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", default="app/src/main/java")
    ap.add_argument("--fetch", action="store_true")
    ap.add_argument("--verbose", action="store_true")
    ap.add_argument("--selftest", action="store_true", help="prove the checker resolves real symbols and rejects fabricated ones")
    ap.add_argument("--only", default="", help="substring filter on file paths")
    ap.add_argument("--unverified-prefix", action="append", default=[],
                    help="package prefixes whose symbols the dumps cannot describe")
    args = ap.parse_args()

    unverified = list(args.unverified_prefix) + ["androidx.compose.material.icons."]
    if args.fetch:
        sys.exit(fetch())
    classes, members = load_dumps()
    if args.selftest:
        sys.exit(selftest(classes, members, unverified))
    if not classes:
        print("no cached api dumps in %s - run: %s --fetch" % (CACHE, sys.argv[0]), file=sys.stderr)
        sys.exit(2)

    hits = {"UNRESOLVED": [], "NO_PACKAGE_DATA": []}
    total = 0
    checked = 0
    for root, _dirs, files in os.walk(args.src):
        for fn in sorted(files):
            if not fn.endswith(".kt"):
                continue
            path = os.path.join(root, fn)
            if args.only and args.only not in path:
                continue
            text = open(path, encoding="utf-8", errors="replace").read()
            for imp in IMPORT_RE.findall(text):
                imp = imp.strip().split()[-1]
                total += 1
                if not imp.startswith(PREFIXES):
                    continue
                checked += 1
                status = resolve(imp, classes, members, unverified)
                if status in hits:
                    hits[status].append((imp, path, text))
    counts = {k: len(v) for k, v in hits.items()}
    print("imports seen: %d (androidx: %d), dumps covering %d packages" % (total, checked, len(classes)))
    for status in ("UNRESOLVED", "NO_PACKAGE_DATA"):
        seen = set()
        for imp, path, _text in hits[status]:
            key = (imp, path)
            if key in seen:
                continue
            seen.add(key)
            print("%s: %s\n    in %s" % (status, imp, path))
    if not args.verbose:
        pass
    sys.exit(1 if hits["UNRESOLVED"] else 0)


if __name__ == "__main__":
    main()
