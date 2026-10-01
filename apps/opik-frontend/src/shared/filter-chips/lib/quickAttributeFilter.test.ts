import { describe, it, expect } from "vitest";
import { Filter } from "@/types/filters";
import { COLUMN_TYPE } from "@/types/shared";
import { TRACE_DATA_TYPE } from "@/constants/traces";
import {
  addQuickFilter,
  resolveQuickFilterTarget,
  stringifyFilterValue,
} from "./quickAttributeFilter";

const SPANS = TRACE_DATA_TYPE.spans;
const TRACES = TRACE_DATA_TYPE.traces;

const metadataFilter = (overrides: Partial<Filter> = {}): Filter => ({
  id: "1",
  field: "metadata",
  type: COLUMN_TYPE.dictionary,
  operator: "contains",
  key: "git.branch",
  value: "main",
  ...overrides,
});

describe("resolveQuickFilterTarget", () => {
  it("targets the metadata field with the path as key", () => {
    expect(resolveQuickFilterTarget("metadata", TRACES, "git.branch")).toEqual({
      chipId: "metadata",
      field: "metadata",
      columnType: COLUMN_TYPE.dictionary,
      key: "git.branch",
    });
  });

  it("prefixes input/output paths and targets the custom field", () => {
    expect(
      resolveQuickFilterTarget("input", SPANS, "messages[0].content"),
    ).toEqual({
      chipId: "custom",
      field: "custom",
      columnType: COLUMN_TYPE.dictionary,
      key: "input.messages[0].content",
    });
  });

  it("keeps the root separator before a bracket-quoted path", () => {
    expect(resolveQuickFilterTarget("output", SPANS, '["a.b"]')).toMatchObject({
      chipId: "custom",
      key: 'output.["a.b"]',
    });
  });

  it("routes a span's root provider to the dedicated provider field", () => {
    expect(resolveQuickFilterTarget("metadata", SPANS, "provider")).toEqual({
      chipId: "provider",
      field: "provider",
      columnType: COLUMN_TYPE.string,
    });
  });

  it("does not offer provider filtering for traces, nor the providers aggregate", () => {
    expect(resolveQuickFilterTarget("metadata", TRACES, "provider")).toBeNull();
    expect(
      resolveQuickFilterTarget("metadata", TRACES, "providers"),
    ).toBeNull();
    expect(
      resolveQuickFilterTarget("metadata", SPANS, "providers[0]"),
    ).toBeNull();
  });

  it("rejects an empty path", () => {
    expect(resolveQuickFilterTarget("metadata", SPANS, "")).toBeNull();
  });
});

describe("stringifyFilterValue", () => {
  it("keeps strings, stringifies numbers/booleans, maps null to empty", () => {
    expect(stringifyFilterValue("main")).toBe("main");
    expect(stringifyFilterValue(0)).toBe("0");
    expect(stringifyFilterValue(true)).toBe("true");
    expect(stringifyFilterValue(null)).toBe("");
  });
});

describe("addQuickFilter", () => {
  const metadataTarget = resolveQuickFilterTarget(
    "metadata",
    TRACES,
    "git.branch",
  )!;

  it("appends a contains row typed for the target field", () => {
    const [row] = addQuickFilter([], metadataTarget, "main");
    expect(row).toMatchObject({
      field: "metadata",
      type: COLUMN_TYPE.dictionary,
      key: "git.branch",
      operator: "contains",
      value: "main",
    });
    expect(row.id).toBeTruthy();
  });

  it("writes an empty key for keyless targets", () => {
    const target = resolveQuickFilterTarget("metadata", SPANS, "provider")!;
    const [row] = addQuickFilter([], target, "openai");
    expect(row).toMatchObject({ field: "provider", key: "", value: "openai" });
  });

  it("keeps filters on other fields and rows of the same field", () => {
    const existing: Filter[] = [
      { ...metadataFilter({ id: "a", key: "env", value: "prod" }) },
      {
        id: "b",
        field: "name",
        type: COLUMN_TYPE.string,
        operator: "=",
        value: "chat",
      },
    ];
    const next = addQuickFilter(existing, metadataTarget, "main");
    expect(next.slice(0, 2)).toEqual(existing);
    expect(next).toHaveLength(3);
  });

  it("returns the same array when an identical row is already applied", () => {
    const existing = [metadataFilter()];
    expect(addQuickFilter(existing, metadataTarget, "main")).toBe(existing);
  });

  it("drops null entries from malformed URL filters", () => {
    const existing = [null, metadataFilter({ key: "env", value: "prod" })];
    const next = addQuickFilter(
      existing as unknown as Filter[],
      metadataTarget,
      "main",
    );
    expect(next).toHaveLength(2);
    expect(next.map((f) => f.key)).toEqual(["env", "git.branch"]);
  });

  it("appends when the same key uses a different operator", () => {
    const existing = [metadataFilter({ operator: "=" })];
    expect(addQuickFilter(existing, metadataTarget, "main")).toHaveLength(2);
  });
});
