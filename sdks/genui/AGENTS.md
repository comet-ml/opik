# Repository Guidelines

## Scope & Inheritance
- This file contains generative UI engine specifics only.
- Follow `../../AGENTS.md` for shared monorepo workflow, PR, and security policy.
- Skill for this folder: `../../.agents/skills/genui/SKILL.md`.

## Project Structure & Module Organization
`sdks/genui` is one npm workspace that builds the engine behind Opik views. A view is model-written HTML, CSS and script `{ type: "html", engine_version, html, inputs, libraries }`; the engine renders it in a sandboxed iframe and gives the authors their guidance. The backend does not depend on the engine.

- `js/src/`: the `opik-genui` npm package, one entry point per file, no index file.
  - `contract.ts`: the HTML contract per `engine_version` (bootstrap, CSP, base stylesheet) in `CONTRACTS`; `ENGINE_VERSION` is the latest key.
  - `HtmlFrame.tsx`: the iframe host. It speaks the MCP Apps message shapes (initialize, initialized, tool-result for the data, host-context-changed for the theme, size-changed, teardown) and stops a view that navigates away.
  - `ViewHost.tsx`: the host wrapper. It refuses an unsupported type, `engine_version`, input kind or library, resolves the inputs and loads the declared libraries through the host adapter in parallel and mounts `HtmlFrame`. A new `fetchInput` or `fetchLibrary` identity means fetch again, so a host keeps them stable with `useCallback` and changes them only when it wants new data, such as a new date range; a host that creates them on every render refetches forever.
  - `tokens.ts`: the `--ov-` token values for light and dark, sent to the view on initialize and on every theme change.
  - `view.ts`: the view and input types, the areas, the libraries, the size cap and the frame height cap.
  - `guidance.ts`: the authoring prompt per area.
  - `styles.css`: the host-side stylesheet for the frame, the loading and the error state, every rule under `.ov-view`.
- `js/libraries/`: one entry per library a view may declare, built into `dist/libraries/<name>.js` as one script that sets its globals. The host fetches the file and the contract inlines it after the bootstrap, only for a view that declares it.
- `js/scripts/`: `emit.ts` writes `python/src/opik_genui/data/` (`engine.json`, `prompt-<area>.txt`); `smoke.ts` is the test suite.
- `python/`: the `opik-genui` Python package: `ENGINE_VERSION`, `AREAS`, `MAX_HTML_LENGTH`, `TOKENS` and `prompt(area)`, read from `python/src/opik_genui/data/` (git-ignored, written by `npm run build`).
- `dev-host/`: a page that renders a fixture view through `ViewHost`, and `dev-host/check.mjs`, the browser check.

## Build, Test, and Development Commands
Run from `sdks/genui`.

- `npm ci` installs every workspace.
- `npm run build` builds the package (tsup, declarations, stylesheet), emits the Python data and builds the dev host.
- `npm run ci` runs what the `checks` CI job runs: lint, typecheck, build, smoke, Python tests.
- `npm run check` runs smoke and the browser check (Chrome from `CHROME`, otherwise Chrome for Testing that `scripts/chrome.mjs` downloads once into `PUPPETEER_CACHE_DIR` or `~/.cache/puppeteer`; offline, set `CHROME`). Run `npm run build` first.
- `npm run lint` runs oxlint and the oxfmt check; `npm run lint:fix` applies the fixes and formats. The `genui-lint` and `genui-typecheck` pre-commit hooks run them on changed files.
- `npm run dev -w dev-host` serves the dev host on port 5191 to try a view by hand.
- `cd python && uv run pytest` runs the Python tests after `npm run build`; `npm run build:python` builds the package and then the wheel.

## Coding Style & Naming Conventions
- TypeScript, 2-space indentation, double quotes, no code comments.
- Nothing in `js/src/` imports from `apps/` or from outside `js/src/`.
- CSS classes carry the `ov-` prefix and every host rule sits under `.ov-view`; tokens carry the `--ov-` prefix.

## Testing Guidelines
- `js/scripts/smoke.ts`: one `[name, boolean]` check per behaviour, run without a browser.
- `dev-host/check.mjs`: data delivery, theme switch, frame height, the forbidden paths (parent access, cookies, WebRTC in the view's window, eval, fetch, images from the network), self-navigation to another origin, script errors and their limit, a large view, an unknown `engine_version` and a chart from a declared library.
- `python/tests/`: the Python package reads what `npm run build` emitted.

## The HTML contract
- `engine_version` is the version of the HTML contract a view was written against. It is an integer, sent by the author and stored with every version.
- A change a stored view could notice is a new contract: the bootstrap, the CSP, the base stylesheet, the message shapes, the `onViewData` and `onViewTheme` callbacks or the meaning of a token name. Add `CONTRACTS[n + 1]` next to the existing ones and leave the old ones as they are; `ENGINE_VERSION` follows. Stored views are never migrated.
- Not a new contract: a new token, a new token value, a change to the guidance, a host-side change in `HtmlFrame.tsx`, `ViewHost.tsx` or `styles.css` that keeps the messages the same.
- A new contract ships with Opik first; Ollie and opik-mcp move to it by upgrading their pin of the packages.

## Areas
- An area is where a view is shown, such as `widget`, a dashboard grid cell. It is fixed when the view is created, and each area gets its own authoring prompt from `prompt(area)`.
- The sandbox, data, style and size rules in `guidance.ts` are the same for every area, because the sandbox is the same. An area only sets the target sentence (where the view sits and how wide it is) and any rules that apply to that area alone.
- To add an area:
  - add it to `AREAS` in `js/src/view.ts`;
  - add its target in `AREA_TARGETS` in `js/src/guidance.ts`, plus its own rules if it has any;
  - add the value to the backend `ViewArea` enum and run `mvn -q test-compile -DskipTests` in `apps/opik-backend`;
  - update the `AREAS` assertion in `python/tests/test_api.py`.
- `npm run build` then writes `prompt-<area>.txt`, Python `prompt(area)` picks it up, and the smoke checks run on every area's prompt.

## Invariants (a red check here is a contract, not a flake)
- `js/src/` imports nothing from Opik: `smoke: no imports from outside the package`.
- A view runs in `sandbox="allow-scripts"` under `default-src 'none'` without `unsafe-eval`, images only from `data:` and `blob:`: `smoke: view CSP allows no network`, `smoke: view CSP allows no eval`, `smoke: frame is sandboxed to scripts only`, `check: forbidden paths stay closed`, `check: no request leaves the view`.
- A view that navigates itself sends nothing out, because the host page limits `frame-src` (or `child-src`) to its own origins; the view's sandbox and CSP do not cover navigation. Every page that mounts `HtmlFrame` sets it, the dev host with `frame-src 'self'`: `check: self-navigation is stopped`, `check: self-navigation sends nothing out`.
- The host accepts messages only from its own iframe window, and the view only from its parent: `smoke: bootstrap checks the message source`.
- Contracts are numbered from 1 without gaps and an unknown `engine_version`, including an inherited object key, renders the error state naming it: `smoke: contracts are numbered from 1 without gaps`, `smoke: inherited object keys are not engine versions`, `check: unknown engine version shows the error state`.
- Light and dark define the same tokens and the guidance lists all of them: `smoke: light and dark define the same tokens`, `smoke: prompt lists every token`.
- `MAX_HTML_LENGTH` in `js/src/view.ts` equals `ViewVersion.MAX_HTML_LENGTH` in the backend, which enforces it on save.

## Agent Contribution Workflow
- This module is part of the Opik monorepo; follow the shared workflow in `../../AGENTS.md#agent-contribution-workflow`.
- Run `npm run ci` before requesting review; `npm run check` for changes to the iframe host or the contract.

## Commit & Pull Request Guidelines
- Follow shared commit/PR policy in `../../AGENTS.md`.
- Use `[SDK]` as the component tag.

## Security & Configuration Tips
- Follow shared security policy in `../../AGENTS.md`.
- The engine never evaluates strings as code, never loads from the network and never renders data as HTML in the host page. Model-written code is contained by the sandbox, not validated.
- Accepted gaps: CPU is not isolated, and WebRTC is disabled only in the view's own window, so a frame nested inside the view can still open a peer connection. The upgrade path for both is a separate sandbox origin.
