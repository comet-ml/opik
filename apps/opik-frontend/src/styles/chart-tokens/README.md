# Chart tokens

`widget-tokens.css` holds the `--chart-*` design tokens for chart widgets. It is vendored from the design source of
truth, the `opik-widgets-prototype` repo (export 1.1.0). It is loaded after `main.scss` and only references primitives
that `main.scss` defines.

The renderer reads the tokens from the live CSS (`src/lib/charts/chartTokens.ts`), so the Vega theme follows the active
theme. About half of the tokens (gantt, bullet, map, funnel, KPI, sparkline, the `-opik` legacy block, the Recharts
aliases) aren't used by Opik yet. They are kept so the file stays a plain copy of the export.

`chart-skeleton.css` is Opik's own: it animates the chart loading placeholders from the `--chart-skeleton-*` tokens.

## Updating the tokens

1. In `opik-widgets-prototype`, run `npm run tokens:export`.
2. Copy the exported `widget-tokens.css` over the one here.
3. Make the edits below. The export doesn't do them yet.
4. Run `npx vitest run src/styles/chart-tokens`.

### Edits the export still needs

- **Remove `--chart-tick-stroke`.** This file isn't inside a CSS layer and `main.scss` is (`@layer base`), so this file
  always wins. If the token stays, it recolours every existing Opik chart axis and the JSON tree popover.
- **Key dark mode on `.dark`.** The export uses `:root[data-theme="dark"]`; Opik's theme provider sets only the `.dark`
  class. `.dark` and `:root` have the same specificity, so the dark block must stay after the light one.
- **Remove `color-scheme: dark`.** It changes native scrollbars, inputs and date pickers across the whole app, not only
  charts.
- **No slash alpha on primitives in dark mode.** Opik's dark primitives are comma-separated (`--background: 0, 0%, 7%`),
  which `hsl(var(--x) / a)` rejects. Use `color-mix(in srgb, hsl(var(--x)) N%, transparent)` instead. This also applies
  to a light-mode token the dark block doesn't redefine.
- **Fix the comment placement.** The exporter moves some comments one token down: a section heading ends up on the last
  token of the section above it (e.g. `--chart-pin-max: 18px; /* Opacity */`).

`widget-tokens.test.ts` catches all of these except the comment placement. It also fails if a token `readChartTokens`
reads is missing from both this file and `main.scss`. A missing token reads as `0` and fails silently, e.g. legend dots
disappear without `--chart-legend-swatch`.

## Known gaps in the 1.1.0 export

These are referenced by the chart rules but not exported; raise them with design:

- `--chart-seq-*`: heatmaps fall back to the neutral tint → `--chart-10`.
- Diverging steps 1, 2, 4 and 5.
- `--chart-*-text` and `--chart-warning-tint`.

The bar max-size tokens aren't applied yet, because Vega-Lite can't cap bar width.
