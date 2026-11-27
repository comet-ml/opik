import { renderHook, waitFor } from "@testing-library/react";
import { expect, it, vi } from "vitest";
import { Trace } from "@/types/traces";
import { useUnifiedMedia } from "./useUnifiedMedia";

vi.mock("@/api/attachments/useAttachmentsList", () => ({
  default: () => ({ isLoading: false }),
}));

it("matches each PDF placeholder to its file when an image comes first", async () => {
  const image = "data:image/png;base64,iVBORw0KGgo=";
  const first = "data:application/pdf;base64,QQ==";
  const second = "data:application/pdf;base64,Qg==";
  const trace = {
    input: { image, first, second },
    output: { second },
  } as Trace;
  const { result } = renderHook(() => useUnifiedMedia(trace));
  await waitFor(() => expect(result.current.isLoading).toBe(false));

  expect(result.current.transformedInput).toEqual({
    image: "[image_0]",
    first: "[pdf_0]",
    second: "[pdf_1]",
  });
  expect(result.current.transformedOutput).toEqual({ second: "[pdf_0]" });
  expect(
    result.current.media.map(({ placeholder, url }) => [placeholder, url]),
  ).toEqual([
    ["[image_0]", image],
    ["[pdf_0]", first],
    ["[pdf_1]", second],
    ["[pdf_0]", second],
  ]);
});
