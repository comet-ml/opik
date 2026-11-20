import { TOKEN_NAMES } from "./tokens";
import {
  MAX_FRAME_HEIGHT,
  MAX_HTML_LENGTH,
  RECOMMENDED_HTML_KB,
  type Area,
  type Library,
} from "./view";

const AREA_TARGETS: Record<Area, string> = {
  widget:
    "a dashboard widget, a grid cell from about 300 px wide up to the full width of the page",
};

const LIBRARY_GUIDANCE: Record<Library, string> = {
  vega: '- "vega": Vega and Vega-Lite as the globals vega and vegaLite, and vegaEmbed(element, spec, options), which returns a promise and already uses the SVG renderer, no actions menu and an expression interpreter that needs no eval. Give the container an explicit width when the spec uses width: "container". Pass theme colours through the spec config, read from the tokens with getComputedStyle(document.documentElement), and embed the chart again from onViewTheme so it follows a theme switch.',
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
    "- Base styles: zero margins, border-box sizing, the --ov-font font, the --ov-fg colour, a transparent background.",
    "- Token roles: --ov-fg for body text, --ov-fg-strong for headings, --ov-fg-muted for labels, --ov-fg-subtle for placeholders and tertiary text; --ov-surface for cards, --ov-bg-subtle for subtle fills such as code and tags; --ov-accent for links and highlights with --ov-on-accent for text on it, --ov-accent-soft and --ov-warning-soft for highlighted backgrounds; --ov-success, --ov-warning and --ov-danger for status; --ov-radius-sm for small elements such as tags.",
    "- Opik views are compact and quiet: 13 to 14 px text, 1px --ov-border lines, --ov-radius corners, 8 to 16 px spacing, no shadows or gradients. When colour tells categories apart, use --ov-chart-1 to --ov-chart-8 in order.",
    "",
    "## Layout",
    "- The same view can be shown in different places and sizes, and its width can change while it is shown. Build it fluid with flex or grid, percentages and minmax; no fixed pixel widths.",
    "- Long text wraps or ends in an ellipsis with the full value in title. Wide content, such as a table or code, scrolls horizontally inside its own container, never the page.",
    "- Anything drawn at a size, such as SVG, canvas or a library drawing, sizes to its container and redraws from a ResizeObserver on it, not from window resize.",
    `- The frame takes the height of the content up to ${MAX_FRAME_HEIGHT.toLocaleString("en-US")} px: do not use 100vh or fixed page heights, and put the most important information first.`,
    "",
    "## Accessibility",
    "- Colour is never the only signal: add a label, a value or an icon. Give a graphic an aria-label or a one-line text summary, and use buttons, not clickable divs, for anything the user acts on.",
    "",
    "## Size",
    `Keep the html small, ideally under ${RECOMMENDED_HTML_KB} KB. A save over ${MAX_HTML_LENGTH.toLocaleString("en-US")} characters is refused.`,
  ].join("\n");
