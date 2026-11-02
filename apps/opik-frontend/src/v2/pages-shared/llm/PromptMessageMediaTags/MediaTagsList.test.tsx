import React from "react";
import { describe, it, expect, vi } from "vitest";
import { render, fireEvent } from "@testing-library/react";
import MediaTagsList from "./MediaTagsList";

vi.mock("@/shared/TooltipWrapper/TooltipWrapper", () => ({
  default: ({
    children,
    content,
  }: {
    children: React.ReactNode;
    content: React.ReactNode;
  }) => (
    <div>
      {children}
      <div data-testid="preview">{content}</div>
    </div>
  ),
}));

const VIDEO_URL = "https://example.com/video.mp4";
const OTHER_VIDEO_URL = "https://example.com/other.mp4";
const AUDIO_URL = "https://example.com/audio.mp3";
const OTHER_AUDIO_URL = "https://example.com/other.mp3";

describe("MediaTagsList", () => {
  it("renders fallback text when a video fails to load", () => {
    const { container, getByText } = render(
      <MediaTagsList type="video" items={[VIDEO_URL]} editable={false} />,
    );

    fireEvent.error(container.querySelector("video")!);

    expect(container.querySelector("video")).toBeNull();
    expect(getByText("Video preview failed")).toBeTruthy();
    expect(getByText(`${VIDEO_URL}...`)).toBeTruthy();
  });

  it("renders fallback text when an audio fails to load", () => {
    const { container, getByText } = render(
      <MediaTagsList type="audio" items={[AUDIO_URL]} editable={false} />,
    );

    fireEvent.error(container.querySelector("audio")!);

    expect(container.querySelector("audio")).toBeNull();
    expect(getByText("Audio preview failed")).toBeTruthy();
  });

  it("retries the video preview when the url changes", () => {
    const { container, rerender } = render(
      <MediaTagsList type="video" items={[VIDEO_URL]} editable={false} />,
    );
    fireEvent.error(container.querySelector("video")!);

    rerender(
      <MediaTagsList type="video" items={[OTHER_VIDEO_URL]} editable={false} />,
    );

    expect(container.querySelector("video")?.getAttribute("src")).toBe(
      OTHER_VIDEO_URL,
    );
  });

  it("retries the audio preview when the url changes", () => {
    const { container, rerender } = render(
      <MediaTagsList type="audio" items={[AUDIO_URL]} editable={false} />,
    );
    fireEvent.error(container.querySelector("audio")!);

    rerender(
      <MediaTagsList type="audio" items={[OTHER_AUDIO_URL]} editable={false} />,
    );

    expect(container.querySelector("audio")?.getAttribute("src")).toBe(
      OTHER_AUDIO_URL,
    );
  });
});
