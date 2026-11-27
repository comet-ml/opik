import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { processInputData } from "@/lib/images";
import { ATTACHMENT_TYPE } from "@/types/attachments";
import { TooltipProvider } from "@/ui/tooltip";
import AttachmentThumbnail from "./AttachmentThumbnail";

const renderPdf = (url: string) => {
  const { media } = processInputData({ document: url });
  expect(media).toHaveLength(1);
  const [pdf] = media;
  expect(pdf).toMatchObject({ type: ATTACHMENT_TYPE.PDF, url });
  const onExpand = vi.fn();
  render(<AttachmentThumbnail previewData={pdf} onExpand={onExpand} />, {
    wrapper: TooltipProvider,
  });
  return { pdf, onExpand };
};

describe("PDF thumbnails for dataset values", () => {
  it.each([
    `${window.location.origin}/files/report.pdf`,
    "https://bucket.s3.amazonaws.com/opik/attachment/report.pdf",
  ])("opens the preview for %s", (url) => {
    const { pdf, onExpand } = renderPdf(url);
    fireEvent.click(screen.getByRole("button", { name: "Open in fullscreen" }));
    expect(onExpand).toHaveBeenCalledWith(pdf);
  });

  it.each([
    "https://external.example/report.pdf",
    "data:application/pdf;base64,JVBERi0xLjQ=",
  ])("offers the original file without opening a preview for %s", (url) => {
    const { pdf, onExpand } = renderPdf(url);
    expect(
      screen.queryByRole("button", { name: "Open in fullscreen" }),
    ).toBeNull();
    fireEvent.click(screen.getByText(pdf.name));
    expect(onExpand).not.toHaveBeenCalled();
    expect(screen.getByRole("link")).toHaveAttribute("href", url);
  });
});
