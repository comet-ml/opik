# Figma Integration Gotchas

Hard-won operational facts. Check here before debugging Figma MCP or pipeline behavior.

## Publishing model (current state)

Code Connect mappings are published exclusively via the CLI from the parser-format files: `npx figma connect publish` (token from `apps/opik-frontend/.env`). It supersedes any mappings previously created through the Figma UI or the MCP API — pass `--force` when the CLI reports existing UI-created mappings. The MCP publish tools (`add_code_connect_map`, `send_code_connect_mappings`) store simple mappings without prop translation; do not use them.

## Diagnosing a stale or simple mapping

In `get_code_connect_map` output, `hasTemplate:false` means only a name+source mapping is attached — `get_design_context` will return raw Figma props (`type="Tertiary"`) instead of code props (`variant="outline"`). The definitive test is whether `get_design_context` returns translated props. Fix by re-running `npx figma connect publish`.

## Remote page-listing is broken on the DS file

The remote MCP page listing on the Opik Design System file returns only "Cover". Reliable enumeration is the desktop bridge: `use_figma` with `figma.root.children` for pages, then `findAllWithCriteria({ types: ["COMPONENT_SET"] })` per page. `node.key` is the stable identifier matching the published `componentKey`. Note `set.componentPropertyDefinitions` is readable in read-only desktop sessions; `importComponentSetByKeyAsync` is a write op and fails there.

## `get_variable_defs` is per-node, not a full export

It returns only the styles/variables *used by that node* — never treat it as a token export (that is `npm run tokens:export`, REST-based). On the DS file it routes to the desktop and needs an actual *layer*; pointing it at a page gives "nothing selected".

## Figma REST token scopes

`tokens:export`, `figma:icons` and publish validation need **Library content: read** in addition to File content read / Code Connect write. Figma's PAT UI also offers "Library **assets**: read" — that is a different scope and does not work.

## Figma asset URLs expire in 7 days

Never leave `figma.com/api/mcp/asset/...` URLs in committed code. Download SVGs into the feature folder or use the mapped `lucide-react` icon.

## Prettier hoisting

Any frontend dep that depends on `prettier ^3.3+` (style-dictionary does) silently bumps the hoisted prettier past the repo's 3.1.1, and the next repo-wide `lint:fix` reformats ~110 files. After ANY `npm install` in `apps/opik-frontend`, run `npm ls prettier` — top level must stay 3.1.1. `npm i -D prettier@3.1.1` re-pins; the dep keeps a nested copy.

## Repo scope boundaries

Tailwind scans `src/**` only (classes in files outside `src` generate no CSS), eslint runs on `src` only, tsconfig includes `src`, `e2e`, `playwright.config.ts`. Place files accordingly — this is why the preview harness component lives in `src/dev-preview/` and the `.figma.tsx` mappings compile under their own `tsconfig.figma.json`.
