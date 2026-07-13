---
name: figma-screen
description: Generate a complete Opik 2.0 screen 1:1 from a Figma frame using the design system — Code Connect mappings translate Figma components to @/ui code, design tokens replace raw values, pixel verification ensures fidelity. Use when asked to implement, build, or generate a screen/page/UI from a Figma frame URL (figma.com/design/... link with a node-id).
---

# Figma Screen Generation

Turn a Figma frame link into a production Opik screen built on the real design system: `@/ui` components via Code Connect, design tokens instead of raw values, and pixel verification against the frame. Nothing gets improvised silently — every gap between the design and the system is collected and reported.

## When NOT to use this skill

- **Design-system component work** (creating/changing `@/ui` primitives or their Code Connect mappings) — that is mapping work in `src/ui/*.figma.tsx`, not screen generation.
- **Updating an existing page** to match a revised frame — reuse the verification loop (steps 6–7) but diff against the existing implementation instead of generating from scratch.
- **A component or icon node** rather than a frame — map it, don't generate a screen from it.

## Prerequisites

- `node_modules` installed in `apps/opik-frontend`. After **any** `npm install` run `npm ls prettier` — top-level must stay `3.1.1` (see [gotchas](gotchas.md#prettier-hoisting)).
- Chrome running if pixel verification goes through the chrome-devtools MCP.
- The dev stack running: `bash scripts/dev-runner.sh --start` (repo root, run once — the frontend serves with hot reload at the URL dev-runner prints, default `http://localhost:5174`; `--verify` checks whether it is already up).
- `apps/opik-frontend/.env` with `FIGMA_ACCESS_TOKEN` is only needed for token/mapping maintenance (`tokens:export`, `figma:icons`, `figma connect publish`), not for generating a screen.

## Workflow

1. **Fetch design context.** Extract `fileKey` and `nodeId` from the frame URL (`figma.com/design/<fileKey>/...?node-id=<a>-<b>` → nodeId `<a>:<b>`) and call the Figma MCP `get_design_context` for that node. Also fetch `get_screenshot` of the same node — it is the comparison target for step 6.
2. **Use mapped components verbatim.** Published mappings arrive wrapped in `CodeConnectSnippet` blocks with translated props and `@/ui` imports (e.g. `<Button variant="outline" size="icon-2xs">`) — use them as-is for the component skeleton. If a snippet shows raw Figma props instead (`type="Tertiary"`, `size="XSmall"`), the published mapping is stale: the co-located `src/ui/<component>.figma.tsx` files are the authoritative translation dictionary — apply them manually, flag "stale publish — rerun `npx figma connect publish`" in your report.
3. **Reuse the DS component — never hand-build a basic element.** If the design shows a Button, Select, Input, Tag, table, etc., use the `@/ui` component. **Do NOT re-implement a primitive with raw `<div>`/`<span>` + Tailwind** (a colored badge, a fake dropdown, a hand-rolled table) even if it looks quick. If the needed component **has no mapping / no matching variant / doesn't exist**, that is a **design-system gap** — use the closest real component and **flag the gap** (step 7). Never hand-tune values to fake a variant that the component doesn't offer.
4. **Apply exact Figma values — never approximate.** Read the exact px / hex / font from `get_design_context` (and `get_variable_defs` for the node). Use them exactly: 32px → `h-8`, not "≈ the nearest size token"; `#373d4d` → the matching token, not a guessed shade. **Rounding an exact value to a nearby token is the #1 cause of fidelity bugs** (it shipped a 28px select when the design said 32px). Prefer a component's real variant (e.g. `<Select size="sm">` = 32px) over any hand-set height. For colors, map to the token via `design-tokens/tokens.json` + `name-map.json`; a value with no token is a gap (step 7), not a raw hex you invent.
5. **Build the screen in v2.** New screens live under `src/v2/` and compose `@/ui` primitives. Fixed pixel widths from Figma become responsive layout (flex/grid, max-widths) unless the design clearly intends a fixed dimension. Never commit Figma asset URLs — they expire in 7 days; download SVGs into the feature folder or use the mapped `lucide-react` icon.
6. **Verify by MEASUREMENT, not eyeball.** Render via the preview harness (below). Then, for the key elements (field/select heights, section headers, badges, icon colors, spacing), **read the rendered computed values via the chrome-devtools MCP** (`evaluate_script` → `getComputedStyle(el).height / color / fontSize`) and **compare each to the exact Figma value from step 4**. Eyeballing screenshots misses sub-4px and dark-vs-light-glyph deltas — those must be caught by comparing numbers. Also screenshot at the frame's exact width for a clean overlay (mismatched widths produce false diffs). Iterate until measured deltas are zero or a listed accepted deviation.
7. **Report.** List every gap found — unmapped component, **missing component variant** (e.g. "Select has no 32px variant"), off-token color, hand-built element, archived component, missing icon, stale publish. These become design-system tickets, not silent workarounds.

## Preview Harness

A generic isolated-render harness is committed at `apps/opik-frontend/figma-preview.html` + `src/dev-preview/`:

1. Overwrite `src/dev-preview/Preview.tsx` **locally** so it renders the screen under construction.
2. Open `<frontend URL>/figma-preview.html` (dev stack already running via dev-runner) and screenshot. Edits hot-reload — no restarts between iterations.
3. **Revert `Preview.tsx` to the committed placeholder before committing** — the overwrite is a local verification aid, never part of the change.

## Hard Rules

- **Reuse DS components; never hand-roll a primitive.** A missing variant is a DS gap to flag, not a `<div>` to build.
- **Exact values, never approximated** — no rounding a Figma px to the nearest token.
- **Verify by measurement** (computed values vs Figma numbers), not by eye.
- **v2 only, never v1** — new screens go under `src/v2/`.
- **Never hardcode a value that has a token.**
- **Lint only the files you touched** — never run repo-wide `lint:fix` or `dev-runner --lint-fe` for this work.

## Reference

- [gotchas.md](gotchas.md) — Figma API quirks, DS-file enumeration, prettier hoisting, publish diagnostics
- Opik Design System file: `https://www.figma.com/design/DQkbgEBm59YiQUzoxxZ0ON/Opik-Design-System` (fileKey `DQkbgEBm59YiQUzoxxZ0ON`) — source of all mappings and tokens
- Mappings: `apps/opik-frontend/src/ui/*.figma.tsx` (parser format, co-located with components; `npm run figma:check` compiles them)
- Tokens: `apps/opik-frontend/design-tokens/` (`tokens.json` DTCG export, `name-map.json` Figma→CSS-variable pairs, `npm run tokens:export|build|reconcile`)
