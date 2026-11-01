import { describe, it, expect } from "vitest";
import { Trace } from "@/types/traces";
import { findTextRanges, traceMatchesSearch } from "./useThreadSearch";

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
