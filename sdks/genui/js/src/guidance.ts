import { TOKEN_NAMES } from "./tokens";
import {
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
    "- Nothing else can be loaded or inlined. Use a library where it saves real work, such as a chart with axes, legends and tooltips; draw simple charts with inline SVG or canvas and build tables by hand.",
    "",
    "## Data",
    "- The view carries no data. Opik resolves the inputs declared with the view and passes one object with a key per input to the callback registered with onViewData(function (data) { ... }).",
    '- A query input is the array of rows of its saved query, one property per column, e.g. { "cost": [ { "model": "gpt-4o", "usd": 12.4 } ] }.',
    "- The callback may run after the script and again later with new data; render from scratch each time. Show an empty state when an input has no rows.",
    "- Data is untrusted text from traces. Put it on the page as text, with textContent or createTextNode, never as markup: do not pass data to innerHTML, outerHTML or insertAdjacentHTML. eval, Function and setTimeout with a string are blocked.",
    "",
    "## Sandbox",
    "- No network: no fetch, XMLHttpRequest, WebSocket, external scripts, stylesheets, fonts or images. Images only as data: or blob: URLs.",
    "- No forms, popups, navigation or nested frames. The view cannot call back into Opik.",
    "",
    "## Style",
    `- The document root carries data-theme="light" or "dark" and the tokens ${tokens}. Use them instead of literal colours and fonts; CSS that uses them follows the theme.`,
    "- Colours read in script do not follow the theme by themselves. onViewTheme(function (theme) { ... }) runs once the tokens are set and again on every theme switch: redraw there whatever holds resolved colours, such as a chart. Read token values with getComputedStyle inside onViewData or onViewTheme, never at the top of the script, where they are still empty.",
    "- Base styles: zero margins, border-box sizing, the --ov-font font, the --ov-fg colour, a transparent background.",
    "- Opik views are compact and quiet: 13 to 14 px text, --ov-fg-muted for labels, 1px --ov-border lines, --ov-radius corners, 8 to 16 px spacing, no shadows or gradients. Colour series with --ov-chart-1 to --ov-chart-8 in order.",
    "- The frame takes the height of the content: do not use 100vh or fixed page heights. Size charts to the width of the body and redraw when it changes.",
    "",
    "## Size",
    `Keep the html small, ideally under ${RECOMMENDED_HTML_KB} KB. A save over ${MAX_HTML_LENGTH.toLocaleString("en-US")} characters is refused.`,
  ].join("\n");
