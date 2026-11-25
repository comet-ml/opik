# Opik generative UI engine

The engine behind Opik views. A view is HTML, CSS and script written by a model; it carries no data and declares inputs that the host resolves from Opik. The engine renders it in a sandboxed iframe, the same way in the Opik frontend, the Ollie console and MCP Apps hosts, and gives the authors the guidance to write it.

One source folder, two packages:

| Package | Built from | Used by |
|---|---|---|
| npm `opik-genui` (`js/`) | `js/src/` | Opik frontend, Ollie console |
| Python `opik-genui` (`python/`) | the guidance and versions that `npm run build` emits into `python/src/opik_genui/data/` | Ollie backend, opik-mcp |

Both packages are published by the Opik release, in one job and with the Opik version, so a version of one always matches the same version of the other. The Opik frontend builds against `js/` in this repository; Ollie and opik-mcp pin released versions.

## Work on it

```
cd sdks/genui
npm ci
npm run build                  # package, Python data, dev host
npm run smoke -w opik-genui    # contract, iframe host, tokens, guidance
npm run check                  # smoke and the browser checks
```

Conventions, the contract rules and the invariants: `AGENTS.md`.
