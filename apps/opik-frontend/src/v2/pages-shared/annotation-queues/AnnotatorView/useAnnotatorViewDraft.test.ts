import { describe, expect, it } from "vitest";
import { act, renderHook } from "@testing-library/react";
import useAnnotatorViewDraft from "./useAnnotatorViewDraft";

const SAVED = {
  html: "<script>kit.mount(() => kit.h('div'))</script>",
  kit_version: 1,
  summary: "Annotators see the answer first.",
};

describe("useAnnotatorViewDraft", () => {
  it("starts from the saved view and is not unsaved", () => {
    const { result } = renderHook(() =>
      useAnnotatorViewDraft({ view: SAVED, isThreadScope: false }),
    );

    expect(result.current.annotatorView).toEqual(SAVED);
    expect(result.current.isUnsaved).toBe(false);
  });

  it("takes the saved view when it arrives after mount", () => {
    const { result, rerender } = renderHook(
      ({ view }: { view?: typeof SAVED }) =>
        useAnnotatorViewDraft({ view, isThreadScope: false }),
      { initialProps: {} },
    );

    expect(result.current.hasView).toBe(false);

    rerender({ view: SAVED });

    expect(result.current.draft.html).toBe(SAVED.html);
    expect(result.current.annotatorView).toEqual(SAVED);
    expect(result.current.isUnsaved).toBe(false);
  });

  it("marks a reset view as unsaved and sends no view", () => {
    const { result } = renderHook(() =>
      useAnnotatorViewDraft({ view: SAVED, isThreadScope: false }),
    );

    act(() => result.current.reset());

    expect(result.current.hasView).toBe(false);
    expect(result.current.annotatorView).toEqual({});
    expect(result.current.isUnsaved).toBe(true);
  });

  it("keeps a draft per scope and restores it when the scope comes back", () => {
    const { result, rerender } = renderHook(
      ({ isThreadScope }) => useAnnotatorViewDraft({ isThreadScope }),
      { initialProps: { isThreadScope: false } },
    );

    act(() => result.current.apply("<div>trace</div>", 1, "Trace view"));
    rerender({ isThreadScope: true });

    expect(result.current.hasView).toBe(false);

    act(() => result.current.apply("<div>thread</div>", 1, "Thread view"));
    rerender({ isThreadScope: false });

    expect(result.current.draft).toEqual({
      html: "<div>trace</div>",
      kitVersion: 1,
      summary: "Trace view",
    });
  });
});
