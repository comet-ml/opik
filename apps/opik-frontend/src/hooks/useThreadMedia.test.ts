import { renderHook } from "@testing-library/react";
import { expect, it } from "vitest";
import { Trace } from "@/types/traces";
import { useThreadMedia } from "./useThreadMedia";

it("keeps audio placeholder numbering independent of PDF inputs", () => {
  const trace = {
    id: "trace-1",
    input: { document: "data:application/pdf;base64,JVBERi0xLjc=" },
    output: { audio: "data:audio/mpeg;base64,SUQz" },
  } as Trace;
  const { result } = renderHook(() => useThreadMedia([trace]));

  expect(result.current.media.map((item) => item.placeholder)).toEqual([
    "[pdf_0]",
    "[audio_0]",
  ]);
});
