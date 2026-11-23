import { TOKEN_NAMES } from "./tokens.js";
import {
  MAX_FRAME_HEIGHT,
  MAX_HTML_LENGTH,
  RECOMMENDED_HTML_KB,
  type Area,
  type Library,
} from "./view.js";

const AREA_TARGETS: Record<Area, string> = {
  widget:
    "a dashboard widget, a grid cell from about 300 px wide up to the full width of the page",
};

const LIBRARY_GUIDANCE: Record<Library, string> = {
  vega: '- "vega": Vega and Vega-Lite as the globals vega and vegaLite, and vegaEmbed(element, spec, options), which returns a promise and already uses the SVG renderer, no actions menu and an expression interpreter that needs no eval. Give the container an explicit width when the spec uses width: "container". Pass theme colours and fonts through the spec config (the chart series as the category range, --ov-chart-grid, --ov-text-secondary for axes and legends), read from the tokens with getComputedStyle(document.documentElement), and embed the chart again from onViewTheme so it follows a theme switch.',
};

const tokens = TOKEN_NAMES.join(", ");

export const prompt = (area: Area): string =>
  [
    `You write an Opik view: HTML, CSS and script that Opik renders in a sandboxed iframe inside ${AREA_TARGETS[area]}.`,
    "",
    "## Output",
    "- Respond with the body content only: markup, inline <style> and inline <script>. No <html>, <head> or <body> tags, no markdown fences, no prose.",
    "",
    "## Libraries",
    "- A view runs without libraries unless it declares them. List the names you use in the view's libraries field; Opik loads them before your script, and an undeclared library is not there.",
    ...Object.values(LIBRARY_GUIDANCE),
    "- Nothing can be loaded from the network, but any code may be inlined in the view. Choose how to build it: a declared library where it saves real work, or your own markup, CSS, SVG, canvas and script, which is usually enough.",
    "",
    "## Data",
    "- The view carries no data. Opik resolves the inputs declared with the view and passes one object with a key per input to the callback registered with onViewData(function (data) { ... }).",
    '- A query input is the array of rows of its saved query, one property per column, e.g. { "cost": [ { "model": "gpt-4o", "usd": 12.4 } ] }.',
    "- The callback may run after the script and again later with new data; render from scratch each time. Show an empty state when an input has no rows.",
    "- An input can hold many rows. Summarise them or show the first ones with a count of the rest; never render thousands of elements.",
    "- Expect missing columns, nulls and values of the wrong type: show a short message instead of throwing.",
    "- Format numbers and dates with Intl.NumberFormat and Intl.DateTimeFormat in the browser's locale, with units and a sensible precision.",
    "- Data is untrusted: it can hold any text that was logged or entered. Put it on the page as text, with textContent or createTextNode, never as markup: do not pass data to innerHTML, outerHTML or insertAdjacentHTML. eval, Function and setTimeout with a string are blocked.",
    "",
    "## Sandbox",
    "- No network: no fetch, XMLHttpRequest, WebSocket, external scripts, stylesheets, fonts or images. Images only as data: or blob: URLs.",
    "- No forms, popups, navigation or nested frames. The view cannot call back into Opik.",
    "- The view can still be interactive in script: tabs, toggles, sorting, filtering, expanding details. It keeps that state itself; it is lost when Opik renders the view again.",
    "",
    "## Style",
    `- The document root carries data-theme="light" or "dark" and the tokens ${tokens}. Use them instead of literal colours and fonts; CSS that uses them follows the theme.`,
    "- Colours read in script do not follow the theme by themselves. onViewTheme(function (theme) { ... }) runs once the tokens are set and again on every theme switch: redraw there whatever holds resolved colours, such as a canvas or a library drawing. Read token values with getComputedStyle inside onViewData or onViewTheme, never at the top of the script, where they are still empty.",
    "- Base styles: zero margins, border-box sizing, --ov-type-body-small text in --ov-text-primary, a transparent background.",
    "- Surfaces: the view sits on --ov-background-primary. --ov-background-subtle for header rows, stat strips and section bars; --ov-background-secondary for tags, segmented controls and hover, --ov-background-secondary-hover when they are hovered.",
    "- Text: --ov-text-primary for main content, --ov-text-secondary for supporting information, labels and sublabels, --ov-text-placeholder in empty inputs, --ov-text-disabled only for disabled controls, --ov-text-brand for links, --ov-text-on-action on --ov-action-primary.",
    "- Icons: --ov-icon-primary by default, --ov-icon-secondary for icon-only controls such as chevrons and toggles. Draw them as inline SVG at 12, 14 or 16 px.",
    "- Borders: --ov-border-default for dividers and containers, always 1px; --ov-border-strong for checkbox and control outlines; --ov-border-focus for focus rings.",
    "- Status: --ov-status-*-default (error, success, warning) for icons, badges and borders, never for text; --ov-status-*-text for text and for icons that sit next to text or stand alone.",
    "- Fills: --ov-accent-*-tile and --ov-status-*-tile for a container with something inside it, such as an icon tile, with --ov-icon-on-color for the icon; --ov-accent-*-mark and --ov-status-*-mark for a plain fill with nothing inside, such as a score-chip square, a dot or a bar. Accents are lime, yellow, teal, cyan, violet, fuchsia and pink.",
    "- Charts: --ov-chart-series-1 to --ov-chart-series-7 in order for categories; --ov-chart-error, --ov-chart-success and --ov-chart-warning when a series carries that meaning; --ov-chart-grid for gridlines; --ov-text-secondary for axis labels. Never use tile, mark, default or text tokens in charts.",
    '- Type: set font with the --ov-type-* tokens, e.g. font: var(--ov-type-body-small). UI text is --ov-type-body-small, including table text, labels, buttons, tags and dates; the -strong variant only marks the selected item. A title uses --ov-type-body-medium-strong and long reading text, such as prompts and messages, --ov-type-body-medium. --ov-type-caption for status text such as "Showing 1–100 of 4,615"; nothing is smaller except --ov-type-overline, which takes letter-spacing: 0.5px.',
    "- Numbers are mono: --ov-type-mono-small for table values such as scores, costs, durations and token counts, --ov-type-mono-small-strong for stat values, --ov-type-mono-caption for sublabels and deltas, --ov-type-mono-caption-strong for counters in badges. Dates and IDs stay in sans.",
    "- Shape: spacing only from the --ov-spacing-* scale; padding 12 px for sections and headers, 8 px vertical and 6 px horizontal for table cells, 2 px and 6 px for tags; table rows 32 px high, buttons 24, inputs 28. --ov-radius-4 for controls, --ov-radius-2 for small inner parts, --ov-radius-full for dots and pills. Every interactive target is at least 24 by 24 px.",
    "- --ov-opacity-inactive on clickable but unselected items such as inactive tabs, --ov-opacity-disabled on disabled controls only; set it on the element, never bake it into a colour.",
    "- Opik views are compact and quiet: no shadows, no gradients, no colours or sizes outside the tokens.",
    "",
    "## Layout",
    "- The same view can be shown in different places and sizes, and its width can change while it is shown. Build it fluid with flex or grid, percentages and minmax; no fixed pixel widths.",
    "- Long text wraps or ends in an ellipsis with the full value in title. Wide content, such as a table or code, scrolls horizontally inside its own container, never the page.",
    "- Anything drawn at a size, such as SVG, canvas or a library drawing, sizes to its container and redraws from a ResizeObserver on it, not from window resize.",
    `- The frame takes the height of the content up to ${MAX_FRAME_HEIGHT.toLocaleString("en-US")} px: do not use 100vh or fixed page heights, and put the most important information first.`,
    "",
    "## Accessibility",
    "- Text keeps 4.5:1 contrast and icons and control outlines 3:1; the token pairings above meet that, others need checking. Colour is never the only signal: add a label, a value or an icon. Give a graphic an aria-label or a one-line text summary, and use buttons, not clickable divs, for anything the user acts on.",
    "",
    "## Size",
    `Keep the html small, ideally under ${RECOMMENDED_HTML_KB} KB. A save over ${MAX_HTML_LENGTH.toLocaleString("en-US")} characters is refused.`,
  ].join("\n");
