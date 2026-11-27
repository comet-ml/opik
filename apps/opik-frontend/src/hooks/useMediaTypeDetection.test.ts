import { renderHook } from "@testing-library/react";
import { expect, it, vi } from "vitest";
import { ATTACHMENT_TYPE } from "@/types/attachments";
import { useMediaTypeDetection } from "./useMediaTypeDetection";

it.each([
  ["https://example.com/report.pdf?thumbnail=cover.png", "report.pdf"],
  ["data:application/pdf;base64,JVBERi0xLjc=", "Base64 PDF"],
  ["data:application/pdf;name=x.pdf;base64,JVBERg==", "Base64 PDF"],
  [
    "See (https://example.com/report.pdf).",
    "report.pdf",
    "https://example.com/report.pdf",
  ],
])("detects %s without fetching", (value, name, url = value) => {
  const fetch = vi.spyOn(globalThis, "fetch").mockRejectedValue(new Error());
  try {
    const { result } = renderHook(() => useMediaTypeDetection(value));
    expect(result.current.mediaData).toEqual({
      url,
      name,
      type: ATTACHMENT_TYPE.PDF,
    });
    expect(fetch).not.toHaveBeenCalled();
  } finally {
    fetch.mockRestore();
  }
});
