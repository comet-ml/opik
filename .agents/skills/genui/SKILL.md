---
name: genui
description: Generative UI engine patterns for Opik. Use when working in sdks/genui, on the iframe host, the HTML contract, the tokens, the authoring guidance or the engine version.
---

# GenUI engine

The engine behind Opik views: an iframe host that renders model-written HTML in a sandbox, the HTML contract the view is written against, the `--ov-` tokens and the authoring guidance, built once in `sdks/genui` for the Opik frontend, the Ollie console, the Python authors and MCP Apps hosts. Read `sdks/genui/AGENTS.md` first.

## Where things live
```
sdks/genui/js/src/contract.ts     CONTRACTS per engine_version, ENGINE_VERSION, the CSP
sdks/genui/js/src/HtmlFrame.tsx   the iframe host, MCP Apps message shapes
sdks/genui/js/src/ViewHost.tsx    the host wrapper: refuses unsupported views, resolves inputs
sdks/genui/js/src/tokens.ts       --ov- token values for light and dark
sdks/genui/js/src/guidance.ts     the authoring prompt per area
sdks/genui/js/src/view.ts         view and input types, areas, libraries, size cap
sdks/genui/js/libraries/          one entry per library a view may declare
sdks/genui/js/scripts/smoke.ts    the test suite
sdks/genui/dev-host/check.mjs     the browser check of the sandbox
sdks/genui/python/                opik-genui: ENGINE_VERSION, AREAS, MAX_HTML_LENGTH, TOKENS, prompt()
```

## Critical Gotchas

### engine_version is the HTML contract version
A change a stored view could notice (bootstrap, CSP, base stylesheet, message shapes, `onViewData`, `onViewTheme`, the meaning of a token) is a new entry in `CONTRACTS`; the old entries stay as they are, because stored views are never migrated. A new token, a token value, the guidance or a host-side change is not. Rules: `sdks/genui/AGENTS.md`, section "The HTML contract".

### The view is contained, not validated
Nobody parses or lints the HTML. Safety comes from `sandbox="allow-scripts"`, the CSP with no network and no eval, the message source checks and the host page's `frame-src`, which alone stops a view navigating itself with data in the URL. Trusted Types is not enforced inside a view, so the guidance tells the author to render data as text. Never loosen them to make a view work; `dev-host/check.mjs` proves each forbidden path stays closed.

### Nothing from Opik
`js/src/` imports nothing from `apps/`. The backend does not depend on the engine either; the only shared value is the size cap, `MAX_HTML_LENGTH` here and `ViewVersion.MAX_HTML_LENGTH` in the backend. The backend checks library names for shape only; which libraries exist is the engine's business, like `engine_version`.

### One entry point per file
The npm package exports `opik-genui/ViewHost`, `opik-genui/HtmlFrame`, `opik-genui/contract`, `opik-genui/tokens`, `opik-genui/view`, `opik-genui/libraries/vega.js` and `opik-genui/styles.css`; add a new public file to `js/package.json` `exports` and `js/tsup.config.ts`. There is no index file.

## Reference Files
- [testing.md](testing.md) - smoke checks, browser checks, Python tests, CI
