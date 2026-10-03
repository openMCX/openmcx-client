#!/usr/bin/env python3
"""Report single-letter names in Python code (docs/CODING_STYLE.md 1.1): variables,
parameters, loop targets, comprehension targets and lambda arguments. ruff has no
rule for this. Usage: tools/check-names.py FILE... ; prints one line per name and
the count; exits 1 when there is any."""

import ast
import sys


def names(tree):
    for node in ast.walk(tree):
        if isinstance(node, ast.arg):
            yield node.arg, node.lineno
        elif isinstance(node, ast.Name) and isinstance(node.ctx, ast.Store):
            yield node.id, node.lineno
        elif isinstance(node, ast.ExceptHandler) and node.name:
            yield node.name, node.lineno


def main():
    found = 0
    for path in sys.argv[1:]:
        with open(path, encoding="utf-8") as source:
            tree = ast.parse(source.read(), path)
        for name, line in names(tree):
            if len(name) == 1 and name != "_":
                print(f"{path}:{line}: single-letter name '{name}'")
                found += 1
    print(f"{found} single-letter names")
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main())
