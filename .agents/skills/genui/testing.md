# GenUI Engine Testing

Run from `sdks/genui` after `npm ci`.

## Smoke suite (`js/scripts/smoke.ts`)
`npm run smoke -w opik-genui`, a few seconds, no browser. One `[name, boolean]` check per behaviour: the contract document and its CSP, the iframe host rendered with `renderToString`, the host wrapper's refusals, the tokens and the guidance. Docs cite a check as `smoke:` followed by its name, so keep names stable.

## Browser check (`dev-host/check.mjs`)
`npm run build`, then `npm run check -w dev-host`. It starts `vite preview` on the dev host, drives Chrome through `puppeteer-core` (Chrome from `CHROME`, otherwise Chrome for Testing that `scripts/chrome.mjs` downloads once), saves screenshots into `dev-host/screenshots/` (git-ignored) and exits non-zero on a failed check or an unexpected console error. It renders `dev-host/fixtures/cost.json`, then replaces the html through the editor to probe the sandbox: parent access, cookies, WebRTC, `eval`, `fetch` and a network image must stay closed, self-navigation must be stopped and a script error must reach the host. A view that declares `vega` must draw a chart. A view is loaded from the URL with `#load=<json of { view, data, theme }>`. `#frame` mounts `HtmlFrame` directly, driven by `window.setFrame({ html, engineVersion })`, to check in-place re-renders, a remount after an unsupported version and recovery from a stop.

## Python tests (`python/tests/`)
`npm run build`, then `cd python && uv sync && uv run pytest`. They read the files `npm run build` emitted into `python/src/opik_genui/data/`; `python/tests/conftest.py` stops with a message when that folder is empty.

## CI (`.github/workflows/genui_tests.yml`)
- `checks`: `npm ci`, `npm run ci` (lint, typecheck, build, smoke, Python tests), then `npm run build:python`.
- `browser`: `npm ci`, `npm run build`, `npm run check -w dev-host` against the runner's Chrome.
