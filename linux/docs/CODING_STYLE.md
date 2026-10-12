# Coding style

This repository follows the OpenMCX coding style: openmcx-server `docs/CODING_STYLE.md` (names, 3GPP references,
separators, file and function size, formatting, tools). The configuration here is the same (`.clang-format`,
`.clang-tidy`, `ruff.toml`), and so are the commands:

| Command | What it does |
|---|---|
| `tools/format [--check]` | clang-format 23 on the C++ sources, ruff format on the Python |
| `tools/lint [--strict] [cpp\|py]` | clang-tidy (with the sibling openmcx-server checkout, `--server PATH`, default `../../openmcx-server`), ruff, `tools/check-names.py` |

Both run in the `openmcx-lint:0.1` image, built by openmcx-server's `tools/lint-image`.
