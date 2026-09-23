import { renderHook, waitFor } from "@testing-library/react";
import { describe, expect, it, vi, beforeEach } from "vitest";
import { ATTACHMENT_TYPE, ParsedMediaData } from "@/types/attachments";
import { useExperimentItemMedia } from "./useExperimentItemMedia";

const attachmentsListMock = vi.fn();
const detectAdditionalMediaMock = vi.fn();

vi.mock("@/api/attachments/useAttachmentsList", () => ({
  default: (...args: unknown[]) => attachmentsListMock(...args),
}));

vi.mock("@/lib/media", () => ({
  detectAdditionalMedia: (...args: unknown[]) =>
    detectAdditionalMediaMock(...args),
}));

const PNG_BASE64 =
  "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

beforeEach(() => {
  attachmentsListMock.mockReset();
  attachmentsListMock.mockReturnValue({ data: undefined, isLoading: false });
  detectAdditionalMediaMock.mockReset();
  detectAdditionalMediaMock.mockImplementation(
    async (_input: unknown, existing: ParsedMediaData[]) => existing,
  );
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

  it("classifies unmapped image mime types as images", async () => {
    attachmentsListMock.mockReturnValue({
      data: {
        content: [
          {
            link: "https://example.com/out.avif",
            file_name: "out.avif",
            file_size: 10,
            mime_type: "image/avif",
          },
        ],
        total: 1,
      },
      isLoading: false,
    });

    const { result } = renderHook(() =>
      useExperimentItemMedia({
        output: {},
        traceId: "trace-1",
        projectId: "project-1",
      }),
    );

    await waitFor(() => expect(result.current.media).toHaveLength(1));

    expect(result.current.media[0].type).toBe(ATTACHMENT_TYPE.IMAGE);
  });

  it("keeps unknown mime types as other", async () => {
    attachmentsListMock.mockReturnValue({
      data: {
        content: [
          {
            link: "https://example.com/data.bin",
            file_name: "data.bin",
            file_size: 10,
            mime_type: "application/x-custom",
          },
        ],
        total: 1,
      },
      isLoading: false,
    });

    const { result } = renderHook(() =>
      useExperimentItemMedia({
        output: {},
        traceId: "trace-1",
        projectId: "project-1",
      }),
    );

    await waitFor(() => expect(result.current.media).toHaveLength(1));

    expect(result.current.media[0].type).toBe(ATTACHMENT_TYPE.OTHER);
  });

  it("includes media detected asynchronously from extensionless urls", async () => {
    detectAdditionalMediaMock.mockImplementation(
      async (_input: unknown, existing: ParsedMediaData[]) => [
        ...existing,
        {
          url: "https://example.com/generated",
          name: "generated",
          type: ATTACHMENT_TYPE.IMAGE,
        } as ParsedMediaData,
      ],
    );

    const { result } = renderHook(() =>
      useExperimentItemMedia({
        output: { url: "https://example.com/generated" },
        traceId: "trace-1",
        projectId: "project-1",
      }),
    );

    await waitFor(() => expect(result.current.media).toHaveLength(1));

    expect(result.current.media[0]).toMatchObject({
      url: "https://example.com/generated",
      type: ATTACHMENT_TYPE.IMAGE,
      source: "inline",
    });
  });

  it("keeps synchronously extracted media when async detection fails", async () => {
    detectAdditionalMediaMock.mockRejectedValue(new Error("network down"));

    const { result } = renderHook(() =>
      useExperimentItemMedia({
        output: { image: PNG_BASE64 },
        traceId: "trace-1",
        projectId: "project-1",
      }),
    );

    await waitFor(() => expect(result.current.media).toHaveLength(1));

    expect(result.current.media[0].url).toBe(PNG_BASE64);
  });

  it("settles instead of re-rendering indefinitely for a stable output", async () => {
    const output = { image: PNG_BASE64 };
    let renders = 0;

    const { result } = renderHook(() => {
      renders++;
      return useExperimentItemMedia({
        output,
        traceId: "trace-1",
        projectId: "project-1",
      });
    });

    await waitFor(() => expect(result.current.media).toHaveLength(1));
    const settledRenders = renders;

    await new Promise((resolve) => setTimeout(resolve, 50));

    expect(renders).toBe(settledRenders);
    expect(renders).toBeLessThan(10);
  });

  it("falls back to the raw output when nothing is extracted", async () => {
    const output = { text: "no media here" };

    const { result } = renderHook(() =>
      useExperimentItemMedia({
        output,
        traceId: "trace-1",
        projectId: "project-1",
      }),
    );

    await waitFor(() =>
      expect(detectAdditionalMediaMock).toHaveBeenCalledTimes(1),
    );

    expect(result.current.media).toHaveLength(0);
    expect(result.current.transformedOutput).toEqual(output);
  });
});
