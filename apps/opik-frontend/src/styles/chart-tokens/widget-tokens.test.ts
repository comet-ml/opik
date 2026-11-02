import { readFileSync } from "node:fs";
import { afterEach, describe, expect, it, vi } from "vitest";
import { readChartTokens } from "@/lib/charts/chartTokens";

const read = (path: string) =>
  readFileSync(new URL(path, import.meta.url), "utf8");
const tokensCss = read("./widget-tokens.css");
const mainScss = read("../../main.scss");

// Guards the vendored token file: a re-export can silently reintroduce or drop names.
const definedIn = (css: string) =>
  new Set(Array.from(css.matchAll(/(--[\w-]+)\s*:/g), (match) => match[1]));

const darkStart = tokensCss.indexOf(".dark {");
const lightBlock = tokensCss.slice(0, darkStart);
const darkBlock = tokensCss.slice(darkStart);
const SLASH_ALPHA = /(--[\w-]+)\s*:[^;]*hsl\(var\(--[\w-]+\)\s*\//g;

describe("widget-tokens.css", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("does not override --chart-tick-stroke, which existing charts take from main.scss", () => {
    expect(definedIn(tokensCss).has("--chart-tick-stroke")).toBe(false);
  });

  it("keys dark mode on the .dark class the theme provider sets", () => {
    expect(darkStart).toBeGreaterThan(0);
    expect(tokensCss).not.toMatch(/data-theme/);
  });

  it("does not set color-scheme, which would change native controls app-wide", () => {
    expect(tokensCss).not.toMatch(/color-scheme/);
  });

  // Dark primitives are comma-separated (`0, 0%, 7%`), which `hsl(var(--x) / a)` rejects.
  it("does not add a slash alpha to a primitive in dark mode", () => {
    expect([...darkBlock.matchAll(SLASH_ALPHA)].map((m) => m[1])).toEqual([]);
    const darkDefined = definedIn(darkBlock);
    const inherited = [...lightBlock.matchAll(SLASH_ALPHA)]
      .map((m) => m[1])
      .filter((name) => !darkDefined.has(name));
    expect(inherited).toEqual([]);
  });

  it("defines every token readChartTokens reads", () => {
    const requested = new Set<string>();
    const getComputedStyle = window.getComputedStyle;
    vi.spyOn(window, "getComputedStyle").mockImplementation((el) => {
      const styles = getComputedStyle(el);
      return new Proxy(styles, {
        get: (target, prop) =>
          prop === "getPropertyValue"
            ? (name: string) => {
                requested.add(name);
                return "";
              }
            : Reflect.get(target, prop),
      });
    });

    readChartTokens();

    const defined = new Set([...definedIn(tokensCss), ...definedIn(mainScss)]);
    expect(requested.size).toBeGreaterThan(0);
    expect([...requested].filter((name) => !defined.has(name))).toEqual([]);
  });
});
