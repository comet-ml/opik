---
name: add-code-quality-hook
description: Recipe for wiring a new linter into Opik's unified 🐙 Code Quality pipeline (pre-commit + CI). Use when adding a pre-commit-driven linter/formatter to the repo — enumerates every file that must change (`.pre-commit-config.yaml`, `scripts/precommit-hook-descriptions.tsv`, `scripts/precommit-detect-hooks.py`, `CONTRIBUTING.md`), the non-obvious gotchas (mandatory explicit `files:`, the blank-description trap, `TOOLCHAIN_BY_ID`/`TYPED_IDS`), the retroactive fix-vs-suppress policy, and the pass/fail verification loop. Worked examples: actionlint (live) and hadolint (OPIK-6673).
---

# Add a Code Quality Hook

Opik runs all linters through **one** pipeline: pre-commit locally, and the `🐙 Code Quality` workflow (`.github/workflows/code_quality.yml`) in CI. CI does not run pre-commit wholesale — it derives, per PR, the set of hooks that actually have work using `scripts/precommit-detect-hooks.py`, then runs **one CI job per matched linter**. That design makes adding a linter a small, fixed set of edits — but each edit is load-bearing, and skipping one produces a *silent* gap (the hook runs nowhere, or renders blank in the summary, or provisions the wrong runtime) rather than a loud failure. This skill is the checklist.

The whole recipe is a generalization of two real PRs: **actionlint** (live in `.pre-commit-config.yaml` today — grep it as you read) and **hadolint** (OPIK-6673, PR #7352 — the first Docker-image hook, `toolchain: none`). Read the actionlint hook alongside this doc; it is the canonical, verifiable example.

## The wiring files

Each linter touches these four files. Do all four.

### 1. `.pre-commit-config.yaml` — add the hook

Add the upstream hook (repo / rev / id). Pin `rev` to a tag or SHA — never a floating ref.

**An explicit `files:` regex is mandatory, not optional.** This is the single most common miss. The CI matrix detector (`precommit-detect-hooks.py`) routes work to hooks by **path regex**, not by pre-commit's `types:`. Most upstream hooks (actionlint, hadolint) ship a `types:`-only match with no `files:`. If you copy them verbatim, the detector cannot route any file to your hook and **CI silently never runs it** — pre-commit locally still works, so the gap hides until something slips through. The detector guards against this: it raises loudly if a hook has `types:`/`types_or:` without `files:` (see `precommit-detect-hooks.py` lines ~115). So a missing `files:` fails the detect step rather than regressing silently — but you still must write the regex.

Write a `files:` regex that captures exactly the paths the linter should gate. Example (actionlint — workflows only):

```yaml
- repo: https://github.com/rhysd/actionlint
  rev: v1.7.12
  hooks:
    - id: actionlint
      name: ⚙️ actionlint — github workflows
      files: ^\.github/workflows/.+\.(yml|yaml)$
```

The `name:` is what the reader and the CI summary see — give it a clear, emoji-prefixed display name matching the house style of the other hooks. The **description keyword you add in step 2 is matched against this name**, so pick a name containing a stable, distinctive substring (e.g. `actionlint`, `hadolint`).

### 2. `scripts/precommit-hook-descriptions.tsv` — add the description row

This TSV is the single source of truth for the per-hook descriptions shown in the Code Quality timing/skipped tables (the CI summary comment). Format: `<keyword>\t<description>`. The keyword is matched as a **substring of the hook display name** (`name:` from step 1).

**Miss this and the hook renders with a blank description in the CI summary.** Add a row:

```
hadolint	Lint Dockerfiles
```

**Order matters — most-specific first.** Matching is first-substring-wins top-to-bottom, so a more specific keyword must precede any that it contains (the file already does this: `ruff-format` before `ruff`). If your keyword is a substring of an existing one, place it above that line.

### 3. `scripts/precommit-detect-hooks.py` — toolchain and content-type maps

Two maps in this file may need an entry. Most new hooks need **neither** (default is `toolchain: none`, no content-type narrowing) — but decide deliberately:

- **`TOOLCHAIN_BY_ID`** — add your hook id here only if the leg's CI job must provision a **heavy runtime**: `java` (shells out to mvn), `node-fe` / `node-ts` (shells out to npm). Pre-commit's own hooks self-provision in isolated envs and need nothing; a `language: golang` hook (actionlint) self-builds; a Docker-image hook (hadolint) runs the image — all of these are `none`. `code_quality.yml` branches its setup steps on `matrix.leg.toolchain`, so a wrong value means a job either wastes minutes provisioning an unused runtime or lacks the runtime it needs.

- **`TYPED_IDS`** — add your hook id here only if it carries an upstream `types:` that narrows its `files:` match to a content **suffix**, so the detector doesn't emit a leg that would no-op at runtime. Value is the tuple of suffixes the hook actually acts on (e.g. `(".py", ".pyi")`). Symptom of a missing entry: the CI timing comment reports fewer ran rows than emitted legs ("detect over-emitted a leg"). If your `files:` regex is already suffix-precise (like actionlint's `\.(yml|yaml)$`), you don't need `TYPED_IDS`.

Rule of thumb by hook type:

| Hook type | `TOOLCHAIN_BY_ID` | `TYPED_IDS` |
|---|---|---|
| Docker-image linter (hadolint) | `none` (omit) | usually omit — make `files:` suffix-precise |
| `language: golang`/self-built (actionlint) | `none` (omit) | omit if `files:` is suffix-precise |
| Python tool (ruff, mypy) | `none` (omit) | add suffixes if `files:` is a broad dir regex |
| Shells out to mvn | `java` | as needed |
| Shells out to npm (FE/TS) | `node-fe` / `node-ts` | as needed |

### 4. `CONTRIBUTING.md` — "how to run locally" note

Add a short section alongside the existing **GitHub Actions workflows** (actionlint) note: what the linter checks, that it runs in the unified `🐙 Code Quality` workflow and locally via pre-commit, and that `make hooks` enables it. If the hook needs a local dependency (a Docker daemon for hadolint, etc.), say so; if pre-commit self-provisions it (actionlint builds from source), say that instead.

## Retroactive step — fix the existing violations

Adding a linter to a repo that has never run it will surface pre-existing violations. **The gate must be green on day one.** Policy, in order of preference:

1. **Fix the violation in place** — this is the default. Most findings are real and worth fixing.
2. **Suppress inline, with a reason, next to the code** — only when a fix would be *genuinely undesirable*. The canonical case: exact-pinning rolling-channel OS packages (e.g. `apt-get install foo=1.2.3`), which rots as mirrors move — the honest answer is an inline `# hadolint ignore=DL3008` with a one-line why. Inline suppression is scoped to that one line and visible in review.
3. **Never a global config-file ignore.** A repo-wide ignore (a `.hadolint.yaml` `ignored:` list, an eslint config-level disable) **fails open on every future file** — it silently exempts code no one has reviewed. Inline-with-reason keeps the gate strict on everything new.

See the OPIK-6673 discussion for a worked case where some rules genuinely couldn't be honestly fixed and inline-with-reason was the right call.

## Verification loop

Before opening the PR, confirm the wiring end-to-end — don't trust that the four edits compose:

1. **Detect emits a leg for a target file.** Run the detector against a file the hook should gate and confirm your hook id appears in `legs` with the right `toolchain`:
   ```bash
   python3 scripts/precommit-detect-hooks.py .pre-commit-config.yaml path/to/target.file
   ```
   If it lands in `skipped` instead, your `files:` regex doesn't match. If it errors about `types:` without `files:`, add the `files:` regex (step 1).

2. **Hook passes clean.** Run it on the current tree and confirm green (this is also the retroactive check):
   ```bash
   pre-commit run <hook-id> --all-files
   ```

3. **Hook fails on a new violation.** Introduce a deliberate violation in a target file and confirm the hook catches it, then revert. A hook that can't fail isn't gating anything.

4. **Description resolves.** Confirm the summary won't render blank:
   ```bash
   echo "<your hook display name>" | python3 scripts/precommit-hook-desc.py
   ```
   Expect `<name>\t<description>` — an empty second column means the keyword row (step 2) is missing or mis-ordered.

## Checklist

- [ ] `.pre-commit-config.yaml`: hook added with pinned `rev` and an **explicit `files:`** regex
- [ ] `scripts/precommit-hook-descriptions.tsv`: keyword→description row, most-specific-first ordering
- [ ] `scripts/precommit-detect-hooks.py`: `TOOLCHAIN_BY_ID` entry iff heavy runtime needed; `TYPED_IDS` entry iff `types:`-narrowed and `files:` isn't suffix-precise
- [ ] `CONTRIBUTING.md`: local "how to run" note added
- [ ] Retroactive: existing violations fixed (or inline-suppressed-with-reason); gate green on day one
- [ ] Verified: detect emits the leg, hook passes clean, hook fails on a new violation, description resolves non-blank
