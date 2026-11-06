# model-prices

The screen logic behind the Cost center tab: API types, the editing reducer, row checks, and the text that describes
changes and history. It has no React, no UI and no imports from outside this folder.

Comet's EM admin dashboard has the same screen (OPIK-9136) and copies this folder as is, so keep it portable:

- Import only from this folder (`./...`). ESLint enforces it.
- No `satisfies`, no inline `import { type X }`, no `const enum`: comet-react's Prettier 2 and Babel reject them.
- Tests use the `describe`/`it`/`expect` globals with no imports, so they run under Vitest here and Jest there.

When you change this folder, copy it to comet-react too.
