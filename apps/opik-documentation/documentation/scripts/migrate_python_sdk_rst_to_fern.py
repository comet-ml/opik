#!/usr/bin/env python3
"""Migrate Python SDK Sphinx .rst pages into Fern MDX pages.

This script preserves narrative prose/examples and strips Sphinx-specific directives.
Generated files are written under docs/reference/python-sdk/sphinx-migrated/**.
"""

from __future__ import annotations

import re
import posixpath
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
SRC_ROOT = ROOT / "apps/opik-documentation/python-sdk-docs/source"
OUT_ROOT = ROOT / "apps/opik-documentation/documentation/fern/docs/reference/python-sdk/sphinx-migrated"

HEADING_CHARS = set("=-~^`\"*+#")
HEADING_LEVEL = {
    "=": 1,
    "-": 2,
    "~": 3,
    "^": 4,
    "`": 5,
    '"': 5,
    "*": 2,
    "+": 3,
    "#": 2,
}

INLINE_LINK_RE = re.compile(r"`([^`]+?)\s*<([^>]+)>`_")
DOC_LINK_RE = re.compile(r":doc:`([^`]+)`")
INLINE_CODE_RE = re.compile(r"``([^`]+)``")


def is_heading_underline(line: str) -> bool:
    stripped = line.strip()
    return bool(stripped) and len(set(stripped)) == 1 and stripped[0] in HEADING_CHARS


def rst_ref_to_slug(ref: str, current_rel: Path) -> str:
    ref = ref.strip()
    if not ref:
        return ""
    if ref.startswith("http://") or ref.startswith("https://"):
        return ref

    rel = Path(ref)
    if rel.suffix == ".rst":
        rel = rel.with_suffix("")
    if not rel.parts:
        return ""

    if not rel.is_absolute():
        joined = posixpath.normpath(f"{current_rel.parent.as_posix()}/{rel.as_posix()}")
        rel = Path(joined)

    rel_str = str(rel).replace("\\", "/")
    return f"/docs/opik/reference/python-sdk/sphinx-migrated/{rel_str}"


def transform_inline(text: str, current_rel: Path) -> str:
    def repl_inline_link(match: re.Match[str]) -> str:
        label, target = match.group(1).strip(), match.group(2).strip()
        target_slug = rst_ref_to_slug(target, current_rel)
        return f"[{label}]({target_slug})"

    def repl_doc_link(match: re.Match[str]) -> str:
        payload = match.group(1).strip()
        if "<" in payload and payload.endswith(">"):
            label, target = payload.split("<", 1)
            label = label.strip()
            target = target[:-1].strip()
        else:
            label = payload.split("/")[-1]
            target = payload
        target_slug = rst_ref_to_slug(target, current_rel)
        return f"[{label}]({target_slug})"

    text = INLINE_LINK_RE.sub(repl_inline_link, text)
    text = DOC_LINK_RE.sub(repl_doc_link, text)
    text = INLINE_CODE_RE.sub(r"`\1`", text)
    return text


def consume_indented_block(lines: list[str], i: int) -> tuple[list[str], int]:
    block: list[str] = []
    n = len(lines)

    while i < n and lines[i].strip() == "":
        block.append("")
        i += 1

    while i < n:
        line = lines[i]
        if line.startswith("   ") or line.startswith("\t"):
            block.append(line[3:] if line.startswith("   ") else line.lstrip("\t"))
            i += 1
            continue
        if line.strip() == "":
            block.append("")
            i += 1
            continue
        break

    while block and block[-1] == "":
        block.pop()
    return block, i


def convert_rst_to_mdx(src_file: Path) -> str:
    rel = src_file.relative_to(SRC_ROOT)
    raw_lines = src_file.read_text(encoding="utf-8").splitlines()
    out: list[str] = []

    title = rel.stem.replace("_", " ").strip().title()
    for idx in range(len(raw_lines) - 1):
        line = raw_lines[idx].rstrip("\n")
        nxt = raw_lines[idx + 1].rstrip("\n")
        if line.strip() and is_heading_underline(nxt):
            title = line.strip()
            break

    out.append("---")
    out.append(f"title: {title}")
    out.append("---")
    out.append("")

    i = 0
    n = len(raw_lines)
    while i < n:
        line = raw_lines[i]
        stripped = line.strip()

        if i + 1 < n and stripped and is_heading_underline(raw_lines[i + 1]):
            level = HEADING_LEVEL.get(raw_lines[i + 1].strip()[0], 2)
            out.append(f"{'#' * level} {transform_inline(stripped, rel)}")
            out.append("")
            i += 2
            continue

        if stripped.startswith(".. toctree::"):
            i += 1
            caption = None
            entries: list[str] = []
            while i < n:
                l = raw_lines[i]
                s = l.strip()
                if s == "":
                    i += 1
                    continue
                if not (l.startswith("   ") or l.startswith("\t")):
                    break
                if s.startswith(":caption:"):
                    caption = s.split(":caption:", 1)[1].strip()
                elif s.startswith(":"):
                    pass
                else:
                    entries.append(s)
                i += 1

            if caption:
                out.append(f"### {caption}")
            else:
                out.append("### References")
            out.append("")
            for entry in entries:
                slug = rst_ref_to_slug(entry, rel)
                label = Path(entry).name.replace(".rst", "").replace("_", " ")
                out.append(f"- [{label}]({slug})")
            out.append("")
            continue

        if stripped.startswith(".. code-block::"):
            lang = stripped.split("::", 1)[1].strip() or "text"
            i += 1
            block, i = consume_indented_block(raw_lines, i)
            out.append(f"```{lang}")
            out.extend(block)
            out.append("```")
            out.append("")
            continue

        if stripped.startswith(".. warning::") or stripped.startswith(".. warning:"):
            i += 1
            block, i = consume_indented_block(raw_lines, i)
            out.append("<Warning>")
            out.extend(transform_inline(b, rel) for b in block)
            out.append("</Warning>")
            out.append("")
            continue

        if stripped.startswith(".. note::") or stripped.startswith(".. note:"):
            i += 1
            block, i = consume_indented_block(raw_lines, i)
            out.append("> Note:")
            out.extend(f"> {transform_inline(b, rel)}" if b else ">" for b in block)
            out.append("")
            continue

        if stripped.startswith(".. automodule::") or stripped.startswith(".. autoclass::") or stripped.startswith(".. autofunction::"):
            symbol = stripped.split("::", 1)[1].strip()
            out.append(f"> API details for `{symbol}` are available in the generated Core API pages.")
            out.append("")
            i += 1
            continue

        if stripped.startswith(".. currentmodule::"):
            mod = stripped.split("::", 1)[1].strip()
            out.append(f"> Current module: `{mod}`")
            out.append("")
            i += 1
            continue

        if stripped == "::" or line.rstrip().endswith("::"):
            prefix = line.rstrip()
            if prefix != "::":
                prefix = prefix[:-1]
            if prefix.strip():
                out.append(transform_inline(prefix, rel))
                out.append("")
            i += 1
            block, i = consume_indented_block(raw_lines, i)
            out.append("```python")
            out.extend(block)
            out.append("```")
            out.append("")
            continue

        if stripped.startswith(".. "):
            i += 1
            continue

        out.append(transform_inline(line, rel))
        i += 1

    while out and out[-1] == "":
        out.pop()
    out.append("")
    return "\n".join(out)


def generate_index(files: list[Path]) -> str:
    grouped: dict[str, list[Path]] = {}
    for f in files:
        rel = f.relative_to(SRC_ROOT).with_suffix("")
        top = rel.parts[0] if len(rel.parts) > 1 else "root"
        grouped.setdefault(top, []).append(rel)

    lines = [
        "---",
        "title: Migrated Sphinx Reference",
        "---",
        "",
        "This section contains migrated content from the legacy Sphinx Python SDK reference. ",
        "API symbol-level details are provided by Fern generated `Core API` pages.",
        "",
    ]

    for group in sorted(grouped):
        label = group.replace("_", " ").title()
        lines.append(f"## {label}")
        lines.append("")
        for rel in sorted(grouped[group]):
            slug = f"/docs/opik/reference/python-sdk/sphinx-migrated/{str(rel).replace('\\\\', '/')}"
            name = rel.name.replace("_", " ")
            lines.append(f"- [{name}]({slug})")
        lines.append("")

    return "\n".join(lines)


def main() -> None:
    rst_files = sorted(p for p in SRC_ROOT.rglob("*.rst"))
    OUT_ROOT.mkdir(parents=True, exist_ok=True)

    for src in rst_files:
        rel = src.relative_to(SRC_ROOT).with_suffix(".mdx")
        out_file = OUT_ROOT / rel
        out_file.parent.mkdir(parents=True, exist_ok=True)
        out_file.write_text(convert_rst_to_mdx(src), encoding="utf-8")

    index_file = OUT_ROOT / "index.mdx"
    index_file.write_text(generate_index(rst_files), encoding="utf-8")

    print(f"Migrated {len(rst_files)} rst files into {OUT_ROOT}")


if __name__ == "__main__":
    main()
