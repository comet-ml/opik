import { describe, it, expect, vi } from "vitest";
import { QuickFilterWidget } from "./quickFilterExtension";

const widget = (overrides: Partial<Record<string, unknown>> = {}) => {
  const props = {
    from: 10,
    to: 20,
    path: "metadata.model",
    value: "opus",
    label: "Filter traces by this attribute",
    ...overrides,
  };
  return new QuickFilterWidget(
    props.from as number,
    props.to as number,
    props.path as string,
    props.value as string,
    vi.fn(),
    props.label as string,
  );
};

// CodeMirror reuses a widget's DOM when eq() is true, so every field written
// into the DOM has to take part in the comparison.
describe("QuickFilterWidget.eq", () => {
  it("matches an identical widget", () => {
    expect(widget().eq(widget())).toBe(true);
  });

  it("separates widgets whose label names a different destination", () => {
    const spans = widget({ label: "Filter spans by this attribute" });
    expect(widget().eq(spans)).toBe(false);
  });

  it.each(["path", "value", "from", "to"])(
    "separates widgets that differ by %s",
    (field) => {
      const changed = {
        path: "metadata.other",
        value: "sonnet",
        from: 11,
        to: 21,
      };
      expect(
        widget().eq(
          widget({ [field]: changed[field as keyof typeof changed] }),
        ),
      ).toBe(false);
    },
  );
});
