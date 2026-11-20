export const PROTOCOL_VERSION = "2026-01-26";
export const VIEW_CSP =
  "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; img-src data: blob:";
const INIT_RETRY_MS = 1000;

const BASE_STYLES =
  ":root{color-scheme:light}:root[data-theme=dark]{color-scheme:dark}html,body{margin:0}*,*::before,*::after{box-sizing:border-box}body{display:flow-root;font:14px/1.4 var(--ov-font,system-ui,sans-serif);color:var(--ov-fg);background:transparent}";

const bootstrapV1 = `
["RTCPeerConnection", "webkitRTCPeerConnection"].forEach(function (name) { Object.defineProperty(window, name, { value: undefined, writable: false, configurable: false }); });
var __parent = parent, __data = null, __received = false, __listeners = [], __theme = null, __themeListeners = [], __id = 0, __pending = {}, __ready = false;
function __send(msg) { msg.jsonrpc = "2.0"; __parent.postMessage(msg, "*"); }
function __log(text) { __send({ method: "notifications/message", params: { level: "error", data: String(text) } }); }
addEventListener("error", function (e) { __log(e.message || "Script error"); });
addEventListener("unhandledrejection", function (e) { __log(e.reason && e.reason.message || e.reason); });
function __notify(listeners, value) { listeners.forEach(function (fn) { try { fn(value); } catch (e) { __log(e && e.message || e); } }); }
function __request(method, params, cb) { var id = ++__id; __pending[id] = cb; __send({ id: id, method: method, params: params }); }
function __applyContext(ctx) {
  if (!ctx) return;
  var root = document.documentElement;
  var vars = ctx.styles && ctx.styles.variables;
  if (vars) for (var k in vars) if (k.indexOf("--") === 0 && typeof vars[k] === "string") root.style.setProperty(k, vars[k]);
  if (!ctx.theme) return;
  root.setAttribute("data-theme", ctx.theme);
  __theme = ctx.theme;
  __notify(__themeListeners, __theme);
}
window.onViewData = function (fn) { __listeners.push(fn); if (__received) __notify([fn], __data); };
window.onViewTheme = function (fn) { __themeListeners.push(fn); if (__theme) __notify([fn], __theme); };
addEventListener("message", function (e) {
  var msg = e.data;
  if (e.source !== __parent || !msg || msg.jsonrpc !== "2.0") return;
  if (msg.id != null && !msg.method) { var cb = __pending[msg.id]; delete __pending[msg.id]; if (cb) cb(msg.result); return; }
  if (msg.id != null && msg.method === "ui/resource-teardown") { __send({ id: msg.id, result: {} }); return; }
  if (msg.id != null) { __send({ id: msg.id, error: { code: -32601, message: "Method not found" } }); return; }
  if (msg.method === "ui/notifications/host-context-changed") { __applyContext(msg.params); return; }
  if (msg.method === "ui/notifications/tool-result") {
    var sc = msg.params && msg.params.structuredContent;
    __data = sc && "data" in sc ? sc.data : null;
    __received = true;
    __notify(__listeners, __data);
  }
});
function __initialize() {
  __request("ui/initialize", {
    appInfo: { name: "opik-genui-view", version: "1" },
    appCapabilities: {},
    protocolVersion: "${PROTOCOL_VERSION}"
  }, __onInitialized);
}
__initialize();
var __retry = setTimeout(function () { if (!__ready) __initialize(); }, ${INIT_RETRY_MS});
function __onInitialized(result) {
  if (__ready) return;
  __ready = true;
  clearTimeout(__retry);
  __applyContext(result && result.hostContext);
  __send({ method: "ui/notifications/initialized", params: {} });
  new ResizeObserver(function () {
    var box = document.body.getBoundingClientRect();
    __send({ method: "ui/notifications/size-changed", params: { width: Math.ceil(box.width), height: Math.ceil(box.height) } });
  }).observe(document.body);
}
`;

const libraryScript = (code: string) =>
  `<script>${code.replace(/<\/script/gi, "<\\/script")}</script>`;

const documentV1 = (html: string, libraries: readonly string[]): string =>
  '<!doctype html><html><head><meta charset="utf-8">' +
  `<meta http-equiv="Content-Security-Policy" content="${VIEW_CSP}">` +
  `<style>${BASE_STYLES}</style>` +
  `<script>${bootstrapV1}</script>` +
  libraries.map(libraryScript).join("") +
  "</head><body>" +
  html +
  "</body></html>";

export const CONTRACTS: ReadonlyMap<
  number,
  (html: string, libraries: readonly string[]) => string
> = new Map([[1, documentV1]]);

export const ENGINE_VERSION = Math.max(...CONTRACTS.keys());

export const buildViewDocument = (
  engineVersion: number,
  html: string,
  libraries: readonly string[] = [],
): string | null => CONTRACTS.get(engineVersion)?.(html, libraries) ?? null;

export const unknownVersionMessage = (engineVersion: number) =>
  `The view was written for HTML contract version ${engineVersion}, which this host does not know; it knows versions 1 to ${ENGINE_VERSION}.`;
