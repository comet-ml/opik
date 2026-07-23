---
name: frontend-design-fidelity
description: >
  Pre-design-review checklist for Opik frontend changes, mined from the design team's recurring
  Slack feedback in #code-review. Use when reviewing any diff under apps/opik-frontend (or the
  opik-plugin-ai-spend FE repo) BEFORE it goes to a designer. Catches the inconsistencies designers
  flag on almost every PR — wrong component variant/size, off-scale typography (raw px, font-weight
  600), arbitrary spacing/hex instead of tokens, missing hover/active/empty/loading/error states,
  missing tooltips on icon buttons, dark-mode contrast, and inconsistent copy/casing. The goal is to
  reduce design review iterations by catching "common inconsistencies" before review, which is what
  the design team explicitly asked for.
---

# Frontend Design Fidelity Review

This is a **design-fidelity reviewer**, not a general code-quality reviewer (see `code-reviewer` and
the `opik-frontend` skill for those). It encodes the concrete, recurring things Opik's designers flag
in review — the class of comment that appears on almost every frontend PR and drives extra review
rounds. Apply it to changed files under `apps/opik-frontend/` before a PR goes for design review.

> Why this exists — from the design team, in their own words:
> *"a lot of inconsistencies in sizes, variant use, gaps, paddings… we're bumping into these in every
> review. The best way to collaborate is to catch these common inconsistencies beforehand to decrease
> the amount of review iterations."*

## Prime directive

**Never freehand a value or component when a canonical one exists.** The design system already defines
the type scale, spacing steps, color tokens, button variants, and list-item styles. The overwhelming
majority of design feedback is some form of "you used a value/variant off the system." Before flagging
the specifics below, ask on every changed component: *is this reusing the blessed primitive, or
re-deriving one?* Re-derived primitives are the #1 source of drift after the 2.0 component rework.

## What to flag

### 1. Component variant misuse & reuse (highest-frequency)

The same primitive exists in several variants post-2.0. Using the wrong one — or rebuilding it — is
the most common flag.

- **Reuse before rebuild.** If a table/list/pill/menu already exists elsewhere, reuse that component
  rather than styling a new one. Flag any hand-rolled element that duplicates an existing primitive
  ("use the same component we have for X", "match the project dropdown", "same table on both pages").
- **Use the smallest correct `Button` size.** Sizes are `3xs | 2xs | xs | sm | default | lg` (icon:
  `icon-3xs … icon-lg`). "Wrong variant (too big)" almost always means a smaller size was intended —
  toolbars/secondary actions are usually `xs`. Don't use `default`/`lg` for dense toolbars.
- **List items:** the canonical menu/list item is the **32px-height variant — blue active state, no
  check icon, narrow paddings** (used in navigation, environment/datepicker menus, prompt library).
  Flag older/taller list-item styles or a check-mark active state in new menus.
- **Pills / tags:** use the smaller pill variant (as in experiments); unify padding with the reference
  table. Flag pills with ad-hoc padding or the larger variant in dense contexts.

### 2. Typography — stay on the scale

The type scale is a fixed set of `comet-*` classes; weights are **font-normal (400) and font-medium
(500) only.**

```
Titles:  comet-title-xl | -l | -m | -s | -xs
Body:    comet-body | comet-body-accented | comet-body-s | comet-body-s-accented | comet-body-xs
Code:    comet-code
```

- ❌ **Raw font sizes / weights.** Flag `text-[14px]`, `text-[16px]`, `font-[600]`, `font-semibold`.
  `600` is **off-scale — "a weight we don't use anywhere."** Map to the class instead.
  ```tsx
  // ❌ BAD
  <span className="text-[14px] font-[600]">AI usage breakdown</span>
  // ✅ GOOD  (body small = 14px; accented = medium/500)
  <span className="comet-body-s-accented">AI usage breakdown</span>
  ```
- **No 14↔12 mixing.** Within one surface, pick `comet-body-s` (14) or `comet-body-xs` (12)
  consistently. Flag a mix of `14px` and `12px` in the same table/panel.
- **Header sizes via `comet-title-*`,** never a raw size. Pick the `comet-title-*` class whose spec
  matches the Figma header style (e.g. "Header XS") rather than hardcoding `text-[16px]`; if no class
  matches the spec'd size, that's a design-system gap to raise, not a one-off literal.

### 3. Spacing & alignment — standard steps only

- ❌ **Arbitrary px.** Flag `p-[13px]`, `gap-[6px]`, `w-[247px]`, `mt-[7px]`. Use Tailwind steps
  (`gap-1 gap-1.5 gap-2 gap-4 gap-6 gap-8`, `p-2 p-4 p-6`). If the Figma spec is an even value
  (8/12/16/32), it maps to a step.
- **Header ↔ content alignment.** Page/panel content must align with the page header's left edge —
  a recurring flag ("main content should be aligned with header", "header + content misaligned").
- **Consistent gaps in forms.** label→field, field→field, field→button should follow the same rhythm;
  flag "gaps don't match our patterns" (one too tight, the next too loose).

### 4. Color — tokens, never literals

- ❌ **Raw hex / arbitrary color.** Flag `#7C3AED`, `bg-[#BAE6FD1A]`, `text-[#5145CD]`,
  `bg-white`, `text-black`, `border-gray-200`.
- ✅ Use theme tokens: `bg-card text-card-foreground border-border`, `text-muted-foreground`,
  `text-muted-slate`, `bg-primary`, `bg-primary-100` (row highlight), `text-primary` (hover accent).
  ```tsx
  // ❌ BAD                                   // ✅ GOOD
  <div className="bg-white text-black">       <div className="bg-card text-card-foreground">
  ```
- New color? Add it to `main.scss`/`tailwind.config.ts` **with a `.dark` value** and reference the
  token — don't inline the hex.

### 5. Dark mode

- Every new color needs a **`.dark` alternative** — flag colors defined only for light mode
  ("bottom bar uses a mix of dark and light mode").
- **Contrast on lighter surfaces.** On lighter backgrounds (e.g. the Ollie sidebar), use
  `text-secondary` not `text-tertiary` — recurring low-contrast flag.
- Verify the change actually in dark mode, not just light.

### 6. Interaction & empty states (frequently forgotten)

- **Every interactive element needs hover / active / focus / disabled.** Flag missing hover on icons,
  links, rows. Icon default→hover is usually `muted-slate` → `primary` (blue).
- **Every list/table/data surface needs empty + loading + error branches.** Flag a happy-path-only
  component. Loading = `Skeleton`; error = a proper error state, not a raw message; empty = the
  designed empty state (often has its own Figma frame — "added missing design for no results").
- **Selected/expanded rows must be highlighted** (`comet-table-row-active` / `bg-primary-100`).
  "Expanded user row should be highlighted", "highlight full row on hover" recur.
- Missing/wrong "thinking"/status states on async UI (Ollie) — verify the state machine renders each
  state, and status-dot colors match (working = gray/orange per spec, not green-for-everything).

### 7. Tooltips & affordances

- **Every icon-only button needs a tooltip.** Flag icon buttons (more-options `···`, filter, refresh)
  with no `Tooltip`. Reuse the existing tooltip component and keep copy consistent ("More options").
- Truncated text needs a full-label tooltip on hover.

### 8. Copy & labels

- **Consistent casing.** Status labels and menu items should follow one casing rule (don't mix
  "Thinking"/"thinking"). Flag regressions to mixed case.
- **Pretty-print identifiers.** Don't surface raw enum/class names — `HierarchicalReflectiveOptimizer`
  → "Hierarchical Reflective". Flag machine identifiers leaking into the UI.
- Match copy to the Figma/ticket when the PR cites one.

## When NOT to flag

Do **not** bikeshed genuine product/UX decisions — flow order, whether to keep a filter, default
operators, "open the dropdown on select". Those are the designer's call and belong in the design
conversation, not a pre-review lint. This reviewer is about *fidelity to the existing system*, not
redesigning the interaction.

## How to report

- Group findings by component/surface; lead with the highest-frequency categories (variant/reuse,
  typography, spacing).
- Give the fix, not just the problem: name the exact class/variant/token to use.
- If the PR references a Figma node, cross-check against it and cite the node id.
- Keep it to fidelity issues — defer correctness/security/perf to `code-reviewer`.

## Maintenance

This skill is mined from real `#code-review` feedback (May–Jul 2026, designers J. Marczewska &
O. Saletska). Refresh it periodically from new design threads so new recurring patterns become rules
and dismissed ones are dropped — the same bubble-in loop the team uses for its other review rules.
