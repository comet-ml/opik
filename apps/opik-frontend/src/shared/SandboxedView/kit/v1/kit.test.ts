import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

const read = (path: string) =>
  readFileSync(new URL(path, import.meta.url), "utf8");

const REFERENCE = read("./REFERENCE.md");
const TOKENS_CSS = read("./tokens.css");
const CORE_JS = read("./core.js");
const REVIEW_JS = read("./packs/review.js");
const OPIK_SCSS = read("../../../../main.scss");

const loadKitApi = () => {
  const win: { kit?: Record<string, unknown> } = {};
  new Function("window", CORE_JS)(win);
  new Function("window", "kit", REVIEW_JS)(win, win.kit);
  return win.kit as Record<string, unknown>;
};

const block = (source: string, selector: string) => {
  const start = source.indexOf(`${selector} {`);
  let depth = 0;
  for (let index = source.indexOf("{", start); index < source.length; index++) {
    if (source[index] === "{") depth++;
    if (source[index] === "}" && --depth === 0) {
      return source.slice(start, index);
    }
  }
  return "";
};

const vars = (css: string) =>
  Object.fromEntries(
    Array.from(css.matchAll(/--([\w-]+):\s*([^;]+);/g), ([, name, value]) => [
      name,
      value.replace(/\s+/g, " ").trim(),
    ]),
  );

const toColor = (value: string) =>
  /^[\d.]+,?\s/.test(value)
    ? `hsl(${value.replace(/,/g, "").replace(/\s+/g, " ")})`
    : value;

const KIT_TO_OPIK: Record<string, string> = {
  "kit-bg": "background",
  "kit-surface": "soft-background",
  "kit-fill": "muted",
  "kit-border": "border",
  "kit-text": "foreground",
  "kit-text-strong": "foreground-secondary",
  "kit-muted": "muted-slate",
  "kit-subtle": "light-slate",
  "kit-off": "slate-300",
  "kit-primary": "primary",
  "kit-primary-soft": "primary-100",
  "kit-on-primary": "primary-foreground",
  "kit-success": "success",
  "kit-danger": "destructive",
  "kit-warning": "warning-box-text",
  "kit-warning-soft": "warning-box-bg",
  "kit-amber": "color-ollie-amber",
  "kit-blue": "chart-blue",
  "kit-green": "chart-green",
  "kit-red": "chart-red",
  "kit-yellow": "chart-yellow",
  "kit-purple": "chart-purple",
  "kit-orange": "chart-orange",
  "kit-teal": "chart-teal",
  "kit-pink": "chart-pink",
};

const INTENDED_DIFFERENCES = ["dark:kit-off", "dark:kit-warning-soft"];

describe("view kit v1", () => {
  it("documents every helper in REFERENCE.md", () => {
    const missing = Object.keys(loadKitApi()).filter(
      (name) => !REFERENCE.includes(`\`kit.${name}`),
    );
    expect(missing).toEqual([]);
  });

  it("documents only helpers that exist", () => {
    const api = loadKitApi();
    const unknown = Array.from(
      REFERENCE.matchAll(/kit\.(\w+)\(/g),
      ([, name]) => name,
    ).filter((name) => !(name in api));
    expect(unknown).toEqual([]);
  });

  it("matches Opik theme colours, or lists the drift to sync by hand", () => {
    const kitLight = vars(block(TOKENS_CSS, ":root"));
    const kitDark = {
      ...kitLight,
      ...vars(block(TOKENS_CSS, ':root[data-theme="dark"]')),
    };
    const opikLight = vars(block(OPIK_SCSS, ":root"));
    const opikDark = { ...opikLight, ...vars(block(OPIK_SCSS, "\n  .dark")) };

    const drift = [
      ["light", kitLight, opikLight] as const,
      ["dark", kitDark, opikDark] as const,
    ].flatMap(([theme, kit, opik]) =>
      Object.entries(KIT_TO_OPIK)
        .filter(
          ([kitName]) => !INTENDED_DIFFERENCES.includes(`${theme}:${kitName}`),
        )
        .filter(
          ([kitName, opikName]) =>
            kit[kitName] !== toColor(opik[opikName] ?? ""),
        )
        .map(
          ([kitName, opikName]) =>
            `${theme}: --${kitName} ${kit[kitName]} vs --${opikName} ${toColor(
              opik[opikName] ?? "",
            )}`,
        ),
    );

    expect(drift).toEqual([]);
  });
});
