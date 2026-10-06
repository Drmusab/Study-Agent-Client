#!/usr/bin/env python3
"""Merge kotlinx-coroutines-test's `common/src` and `jvm/src` into one JVM compilation unit.

Why this exists
---------------
`kotlinx-coroutines-test` is a KMP module: `common/src` declares `expect` and `jvm/src` declares the
matching `actual`. Gradle compiles those source sets separately. The harness has a single CLI
compiler and a single output directory, and Kotlin 2.3 refuses same-module `expect`/`actual`
("expect and corresponding actual are declared in the same module") — `-Xmulti-platform` and
`-Xexpect-actual-classes` do not lift that rule. This script therefore removes the five `expect`
declarations (the JVM `actual` implementations stay) and strips the `actual` modifier; nothing else
is changed.

Usage
-----
    python3 prep-coroutines-test.py <downloaded-checkout> <output-src-dir>

Input is `kotlinx-coroutines-test/` from the tag matching the coroutines version the harness runs
(see tools/jvm-harness/README.md). Compile the output directory with `bin/kc`, then put the module's
`jvm/resources/META-INF` directory into the jar: without those service files `runTest` fails with
`IllegalStateException: Exception handler was not found via a ServiceLoader`. `bin/bootstrap-sandbox.sh`
runs both steps in order.

The input tree is only read, never patched in place. Nothing is downloaded here.
"""

import os
import re
import shutil
import sys

EXPECT = re.compile(r'^\s*(?:(?:public|internal|private|protected)\s+)?expect\b')
ACTUAL = re.compile(r'\bactual\s+')


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    src_root, dst_root = sys.argv[1], sys.argv[2]
    if not os.path.isdir(os.path.join(src_root, 'common', 'src')):
        print(f'{src_root} does not look like a kotlinx-coroutines-test module', file=sys.stderr)
        return 2
    if os.path.exists(dst_root):
        shutil.rmtree(dst_root)

    dropped, stripped = [], []
    for source_set in ('common/src', 'jvm/src'):
        for base, _, files in os.walk(os.path.join(src_root, source_set)):
            for name in files:
                if not name.endswith('.kt'):
                    continue
                path = os.path.join(base, name)
                relative = os.path.relpath(path, src_root)
                out, pending = [], []
                with open(path, encoding='utf-8') as handle:
                    for number, line in enumerate(handle, 1):
                        if EXPECT.match(line):
                            # A declaration-only line; annotations attached to it must go too, or
                            # the orphaned `@Suppress(...)` becomes a syntax error.
                            assert '{' not in line and '}' not in line, f'{path}:{number}: multi-line expect'
                            dropped.append(f'{relative}:{number}')
                            pending = []
                            continue
                        if line.lstrip().startswith('@'):
                            pending.append(line)
                            continue
                        out.extend(pending)
                        pending = []
                        if ACTUAL.search(line):
                            stripped.append(f'{relative}:{number}')
                            line = ACTUAL.sub('', line)
                        out.append(line)
                target = os.path.join(dst_root, relative)
                os.makedirs(os.path.dirname(target), exist_ok=True)
                with open(target, 'w', encoding='utf-8') as handle:
                    handle.write(''.join(out))

    print('dropped expect declarations:', dropped)
    print('stripped actual modifiers:', stripped)
    print('copied to', dst_root)
    print('note: the jar needs jvm/resources/META-INF/services or runTest fails at runtime')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
