import { readdirSync, readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { createElement } from "react";
import { renderToString } from "react-dom/server";
import {
  CONTRACTS,
  ENGINE_VERSION,
  VIEW_CSP,
  buildViewDocument,
} from "../src/contract";
import { prompt } from "../src/guidance";
import { HtmlFrame } from "../src/HtmlFrame";
import { TOKENS, TOKEN_NAMES } from "../src/tokens";
import { ViewHost, unsupportedReason } from "../src/ViewHost";
import {
  AREAS,
  LIBRARIES,
  MAX_FRAME_HEIGHT,
  MAX_HTML_LENGTH,
  type Library,
  type View,
} from "../src/view";

const srcDir = fileURLToPath(new URL("../src", import.meta.url));

const sourceFiles = (dir: string): string[] =>
  readdirSync(dir, { withFileTypes: true }).flatMap((entry) =>
    entry.isDirectory()
      ? sourceFiles(resolve(dir, entry.name))
      : /\.(ts|tsx)$/.test(entry.name)
        ? [resolve(dir, entry.name)]
        : [],
  );

const outsideImports = sourceFiles(srcDir).flatMap((file) =>
  [...readFileSync(file, "utf8").matchAll(/from "([^"]+)"/g)]
    .map((m) => m[1])
    .filter(
      (spec) =>
        spec.startsWith("@/") ||
        (spec.startsWith(".") &&
          !resolve(dirname(file), spec).startsWith(srcDir)),
    )
    .map((spec) => `${file.slice(srcDir.length)} -> ${spec}`),
);

const view: View = {
  type: "html",
  engine_version: ENGINE_VERSION,
  html: "<p>Hi</p>",
  inputs: { cost: { kind: "query", query_id: "q-cost" } },
};
const html = (props: Partial<Parameters<typeof HtmlFrame>[0]>) =>
  renderToString(
    createElement(HtmlFrame, {
      html: "<p>Hi</p>",
      engineVersion: ENGINE_VERSION,
      data: {},
      theme: "light",
      ...props,
    }),
  );
const host = (theme: "light" | "dark") =>
  renderToString(
    createElement(ViewHost, {
      view,
      adapter: { fetchInput: () => Promise.resolve([]), theme },
    }),
  );

const document = buildViewDocument(ENGINE_VERSION, "<p>Hi</p>") ?? "";
const withLibrary =
  buildViewDocument(ENGINE_VERSION, "<p>Hi</p>", ["var lib = '</script>';"]) ??
  "";
const frame = html({});
const decoded = (s: string) =>
  s
    .replace(/&#x27;/g, "'")
    .replace(/&quot;/g, '"')
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&amp;/g, "&");
const prompts = AREAS.map(prompt);

const checks: [string, boolean][] = [
  ["no imports from outside the package", outsideImports.length === 0],
  ["engine version is the latest contract", ENGINE_VERSION === 1],
  [
    "contracts are numbered from 1 without gaps",
    [...CONTRACTS.keys()].every((v, i) => v === i + 1),
  ],
  ["unknown engine version has no document", !buildViewDocument(0, "x")],
  [
    "inherited object keys are not engine versions",
    ["constructor", "__proto__", "toString", "1"].every(
      (v) => !buildViewDocument(v as unknown as number, "x"),
    ),
  ],
  [
    "view CSP allows no network",
    VIEW_CSP.startsWith("default-src 'none';") &&
      !/connect-src|font-src|frame-src|https?:/.test(VIEW_CSP),
  ],
  ["view CSP allows no eval", !VIEW_CSP.includes("unsafe-eval")],
  [
    "view CSP limits images to data and blob",
    VIEW_CSP.includes("img-src data: blob:"),
  ],
  [
    "view document carries the CSP before any script",
    document.indexOf(VIEW_CSP) > 0 &&
      document.indexOf(VIEW_CSP) < document.indexOf("<script>"),
  ],
  [
    "view document ends with the html in the body",
    document.endsWith("<body><p>Hi</p></body></html>"),
  ],
  [
    "bootstrap closes no script tag early",
    (document.match(/<\/script>/g) ?? []).length === 1,
  ],
  [
    "libraries are inlined after the bootstrap and before the html",
    withLibrary.indexOf("window.onViewData") < withLibrary.indexOf("var lib") &&
      withLibrary.indexOf("var lib") < withLibrary.indexOf("<body>"),
  ],
  [
    "a library closes no script tag early",
    (withLibrary.match(/<\/script>/g) ?? []).length === 2,
  ],
  ["bootstrap exposes onViewData", document.includes("window.onViewData")],
  ["bootstrap exposes onViewTheme", document.includes("window.onViewTheme")],
  [
    "bootstrap checks the message source",
    document.includes("e.source !== __parent"),
  ],
  [
    "bootstrap sizes from the body",
    document.includes("document.body.getBoundingClientRect()"),
  ],
  [
    "bootstrap reports script errors",
    document.includes("notifications/message"),
  ],
  [
    "base styles use the tokens",
    document.includes("var(--ov-font") && document.includes("var(--ov-fg)"),
  ],
  [
    "frame is sandboxed to scripts only",
    frame.includes('sandbox="allow-scripts"'),
  ],
  ["frame starts at the initial height", frame.includes("height:120px")],
  [
    "frame renders the view document",
    decoded(frame).includes(VIEW_CSP) && decoded(frame).includes("<p>Hi</p>"),
  ],
  [
    "unknown engine version renders an error naming it",
    html({ engineVersion: 9 }).includes(
      "written for HTML contract version 9, which this host does not know",
    ),
  ],
  [
    "light and dark define the same tokens",
    JSON.stringify(Object.keys(TOKENS.dark).sort()) ===
      JSON.stringify([...TOKEN_NAMES].sort()),
  ],
  [
    "every token carries the --ov- prefix",
    TOKEN_NAMES.every((name) => name.startsWith("--ov-")),
  ],
  [
    "host wraps the view with the theme and its tokens",
    host("dark").startsWith('<div class="ov-view" data-theme="dark"') &&
      host("dark").includes(`--ov-bg:${TOKENS.dark["--ov-bg"]}`),
  ],
  [
    "host shows loading before the data",
    host("light").includes("Loading view data"),
  ],
  ["supported view has no reason", unsupportedReason(view) === null],
  [
    "unknown type is refused",
    unsupportedReason({ ...view, type: "markdown" as "html" }) ===
      'The view type "markdown" is not supported.',
  ],
  [
    "unknown engine version is refused",
    unsupportedReason({ ...view, engine_version: 2 })?.includes(
      "version 2, which this host does not know",
    ) === true,
  ],
  [
    "unknown input kind is refused",
    unsupportedReason({
      ...view,
      inputs: { item: { kind: "trace" as "query", query_id: "" } },
    }) === 'The input kind "trace" is not supported.',
  ],
  [
    "unknown library is refused",
    unsupportedReason({ ...view, libraries: ["d3" as Library] }) ===
      'The library "d3" is not supported.',
  ],
  [
    "prompt names every library",
    prompts.every((p) => LIBRARIES.every((name) => p.includes(`"${name}"`))),
  ],
  [
    "prompt lists every token",
    prompts.every((p) => TOKEN_NAMES.every((name) => p.includes(name))),
  ],
  [
    "prompt describes the data callback",
    prompts.every((p) => p.includes("onViewData(function (data)")),
  ],
  [
    "prompt describes the theme callback",
    prompts.every((p) => p.includes("onViewTheme(function (theme)")),
  ],
  [
    "prompt asks for a fluid layout and states the frame height cap",
    prompts.every(
      (p) =>
        p.includes("## Layout") &&
        p.includes(`${MAX_FRAME_HEIGHT.toLocaleString("en-US")} px`),
    ),
  ],
  [
    "prompt states the size cap",
    prompts.every((p) =>
      p.includes(`${MAX_HTML_LENGTH.toLocaleString("en-US")} characters`),
    ),
  ],
];

let failed = 0;
for (const [name, ok] of checks) {
  console.log(`${ok ? "ok  " : "FAIL"} ${name}`);
  if (!ok) failed++;
}
for (const line of outsideImports) console.log(`     ${line}`);
console.log(`${checks.length - failed}/${checks.length} checks passed`);
process.exit(failed ? 1 : 0);
