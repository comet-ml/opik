import { renderHook, waitFor } from "@testing-library/react";
import { describe, expect, it, vi, beforeEach } from "vitest";
import { ATTACHMENT_TYPE } from "@/types/attachments";
import { useExperimentItemMedia } from "./useExperimentItemMedia";

const attachmentsListMock = vi.fn();

vi.mock("@/api/attachments/useAttachmentsList", () => ({
  default: (...args: unknown[]) => attachmentsListMock(...args),
}));

const PNG_BASE64 =
  "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

beforeEach(() => {
  attachmentsListMock.mockReset();
  attachmentsListMock.mockReturnValue({ data: undefined, isLoading: false });
});

describe("useExperimentItemMedia", () => {
  it("queries attachments for the originating trace, not the experiment item", () => {
    renderHook(() =>
      useExperimentItemMedia({
        output: {},
        traceId: "trace-1",
        projectId: "project-1",
      }),
    );

    expect(attachmentsListMock).toHaveBeenCalledWith(
      expect.objectContaining({
        projectId: "project-1",
        id: "trace-1",
        type: "trace",
      }),
      expect.objectContaining({ enabled: true }),
    );
  });

  it("does not query attachments when the project id is unknown", () => {
    renderHook(() =>
      useExperimentItemMedia({
        output: {},
        traceId: "trace-1",
        projectId: undefined,
      }),
    );

    expect(attachmentsListMock).toHaveBeenCalledWith(
      expect.anything(),
      expect.objectContaining({ enabled: false }),
    );
  });

  it("exposes backend attachments as media so placeholders can resolve", async () => {
    attachmentsListMock.mockReturnValue({
      data: {
        content: [
          {
            link: "https://example.com/out.png",
            file_name: "output-attachment-1.png",
            file_size: 10,
            mime_type: "image/png",
          },
        ],
        total: 1,
      },
      isLoading: false,
    });

    const { result } = renderHook(() =>
      useExperimentItemMedia({
        output: { result: "[output-attachment-1.png]" },
        traceId: "trace-1",
        projectId: "project-1",
      }),
    );

    await waitFor(() => expect(result.current.media).toHaveLength(1));

    expect(result.current.media[0]).toMatchObject({
      url: "https://example.com/out.png",
      name: "output-attachment-1.png",
      type: ATTACHMENT_TYPE.IMAGE,
      source: "attachment",
      placeholder: "[output-attachment-1.png]",
    });
  });

  it("extracts inline base64 media from the output", async () => {
    const { result } = renderHook(() =>
      useExperimentItemMedia({
        output: { image: PNG_BASE64 },
        traceId: "trace-1",
        projectId: "project-1",
      }),
    );

    await waitFor(() => expect(result.current.media).toHaveLength(1));

    expect(result.current.media[0]).toMatchObject({
      url: PNG_BASE64,
      type: ATTACHMENT_TYPE.IMAGE,
      source: "inline",
    });
  });

  it("falls back to the raw output when nothing is extracted", () => {
    const output = { text: "no media here" };

    const { result } = renderHook(() =>
      useExperimentItemMedia({
        output,
        traceId: "trace-1",
        projectId: "project-1",
      }),
    );

    expect(result.current.media).toHaveLength(0);
    expect(result.current.transformedOutput).toEqual(output);
  });
});
