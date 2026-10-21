import { describe, expect, it } from "vitest";
import { act, renderHook } from "@testing-library/react";
import { AnnotationQueueAnnotatorView } from "@/types/annotation-queues";
import useAnnotatorViewDraft from "./useAnnotatorViewDraft";

const renderDraft = (
  view: AnnotationQueueAnnotatorView,
  isThreadScope = false,
) => renderHook(() => useAnnotatorViewDraft({ view, isThreadScope }));

describe("useAnnotatorViewDraft", () => {
  const view = {
    input: "messages[-1].content",
    fields: [{ label: " Source ", path: "input.text", half_width: true }],
  };

  it("trims labels for trace queues", () => {
    const { result } = renderDraft(view);

    expect(result.current.annotatorView).toEqual({
      input: "messages[-1].content",
      output: undefined,
      fields: [{ label: "Source", path: "input.text", half_width: true }],
    });
  });

  it("drops fields for thread queues", () => {
    const { result } = renderDraft(view, true);

    expect(result.current.annotatorView).toEqual({
      input: "messages[-1].content",
      output: undefined,
    });
  });

  it("flags a blank label only for trace queues", () => {
    const blank = { fields: [{ label: " ", path: "input" }] };

    expect(renderDraft(blank).result.current.isInvalid).toBe(true);
    expect(renderDraft(blank, true).result.current.isInvalid).toBe(false);
  });

  it("reflects edits to the draft", () => {
    const { result } = renderDraft({});

    act(() =>
      result.current.setDraft({ ...result.current.draft, output: "answer" }),
    );

    expect(result.current.annotatorView.output).toBe("answer");
  });

  it("leaves fields out while they match the defaults", () => {
    const { result } = renderDraft({});

    expect(result.current.annotatorView).toEqual({
      input: undefined,
      output: undefined,
    });
  });

  it("saves fields once they differ from the defaults", () => {
    const { result } = renderDraft({});

    act(() =>
      result.current.setDraft({
        ...result.current.draft,
        fields: result.current.draft.fields.slice(1),
      }),
    );

    expect(result.current.annotatorView.fields).toHaveLength(3);
  });

  it("is dirty only while the draft differs from the loaded view", () => {
    const { result } = renderDraft({ input: "messages[-1].content" });
    expect(result.current.isDirty).toBe(false);

    const loaded = result.current.draft;
    act(() => result.current.setDraft({ ...loaded, output: "answer" }));
    expect(result.current.isDirty).toBe(true);

    act(() => result.current.setDraft({ ...loaded }));
    expect(result.current.isDirty).toBe(false);
  });
});
