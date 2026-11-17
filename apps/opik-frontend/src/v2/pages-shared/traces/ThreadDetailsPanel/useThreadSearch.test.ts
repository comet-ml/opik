import { afterEach, describe, it, expect, vi } from "vitest";
import { Trace } from "@/types/traces";
import {
  createMatchHighlight,
  findTextRanges,
  scrollRangeIntoView,
  traceMatchesSearch,
} from "./useThreadSearch";

const makeTrace = (input: Trace["input"], output: Trace["output"]): Trace =>
  ({ id: "trace-1", input, output }) as Trace;

const container = (html: string) => {
  const root = document.createElement("div");
  root.innerHTML = html;
  return root;
};

describe("traceMatchesSearch", () => {
  it("matches the prettified input or output, ignoring case", () => {
    const trace = makeTrace(
      { input: "Plan the Route" },
      { output: "Route planned" },
    );

    expect(traceMatchesSearch(trace, "the route")).toBe(true);
    expect(traceMatchesSearch(trace, "PLANNED")).toBe(true);
    expect(traceMatchesSearch(trace, "scenario")).toBe(false);
  });

  it("matches inside structured messages", () => {
    const trace = makeTrace(
      { messages: [{ role: "user", content: "hi" }] },
      { tool_calls: [{ name: "lookup_scenario", args: { id: 12 } }] },
    );

    expect(traceMatchesSearch(trace, "lookup_scenario")).toBe(true);
  });

  it("keeps a turn only when its text yields highlight ranges", () => {
    // "İ".toLowerCase() contains "i", but the case-insensitive regex does not match it.
    const trace = makeTrace({ input: "İstanbul" }, { output: "" });
    const root = container("<p>İstanbul</p>");

    expect(traceMatchesSearch(trace, "i")).toBe(
      findTextRanges([root], "i").length > 0,
    );
  });

  // JsonView renders keys and values unescaped; stringified JSON doubles backslashes and
  // escapes quotes, which hid turns whose visible text matched.
  it("matches structured values as they are rendered, not as escaped JSON", () => {
    const trace = makeTrace(
      { path: "C:\\Users\\bob\\report.txt", mode: "read" },
      { reply: 'say "hi"', status: "sent" },
    );

    expect(traceMatchesSearch(trace, "C:\\Users")).toBe(true);
    expect(traceMatchesSearch(trace, 'say "hi"')).toBe(true);
    expect(traceMatchesSearch(trace, "mode")).toBe(true);
  });

  it("matches nothing for a blank search, like findTextRanges", () => {
    const trace = makeTrace({ input: "two  spaces" }, { output: "" });

    expect(traceMatchesSearch(trace, "")).toBe(false);
    expect(traceMatchesSearch(trace, "  ")).toBe(false);
  });
});

describe("findTextRanges", () => {
  it("finds every case-insensitive match across roots and text nodes", () => {
    const first = container("<p>Error here</p><p>no <b>error</b> there</p>");
    const second = container("<span>ERROR, error</span>");

    const ranges = findTextRanges([first, second], "error");

    expect(ranges.map((range) => range.toString())).toEqual([
      "Error",
      "error",
      "ERROR",
      "error",
    ]);
  });

  it("keeps match offsets right after characters whose lowercase form is longer", () => {
    // "İ".toLowerCase() is two code units, so offsets taken from lowercased text drift.
    const root = container("<p>İstanbul route</p>");

    const ranges = findTextRanges([root], "ROUTE");

    expect(ranges.map((range) => range.toString())).toEqual(["route"]);
  });

  it("finds nothing for a blank search", () => {
    const root = container("<p>some text</p>");

    expect(findTextRanges([root], "")).toEqual([]);
    expect(findTextRanges([root], "   ")).toEqual([]);
  });

  it("does not search outside the given roots", () => {
    const root = container(
      "<div data-message-content>answer</div><button>answer</button>",
    );

    const ranges = findTextRanges(
      Array.from(root.querySelectorAll("[data-message-content]")),
      "answer",
    );

    expect(ranges).toHaveLength(1);
  });
});

describe("createMatchHighlight", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  // A one- or two-letter search on a large thread yields hundreds of thousands of matches;
  // spreading them into `new Highlight(...)` threw a RangeError and unmounted the panel.
  it("holds more matches than fit in a function call's arguments", () => {
    vi.stubGlobal("Highlight", class extends Set<Range> {});
    const ranges = Array.from({ length: 500_000 }, () => ({}) as Range);

    expect(createMatchHighlight(ranges).size).toBe(ranges.length);
  });
});

describe("scrollRangeIntoView", () => {
  it("centers the match itself inside the scroll container", () => {
    const scroller = container("<p>line<br>needle</p>");
    scroller.style.overflowY = "auto";
    Object.defineProperties(scroller, {
      scrollHeight: { value: 4000 },
      clientHeight: { value: 400 },
      scrollTop: { value: 1000, writable: true },
    });
    scroller.getBoundingClientRect = () => ({ top: 100 }) as DOMRect;
    scroller.scrollTo = vi.fn() as typeof scroller.scrollTo;
    document.body.appendChild(scroller);

    const [range] = findTextRanges([scroller], "needle");
    range.getBoundingClientRect = () => ({ top: 900, height: 20 }) as DOMRect;

    scrollRangeIntoView(range);

    // 1000 + (900 - 100) - (400 - 20) / 2
    expect(scroller.scrollTo).toHaveBeenCalledWith({ top: 1610 });
    scroller.remove();
  });

  it("falls back to centering the match's element when nothing scrolls", () => {
    const root = container("<p>line <b>needle</b></p>");
    document.body.appendChild(root);
    const element = root.querySelector("b")!;
    element.scrollIntoView = vi.fn();

    const [range] = findTextRanges([root], "needle");
    scrollRangeIntoView(range);

    expect(element.scrollIntoView).toHaveBeenCalledWith({ block: "center" });
    root.remove();
  });
});
