import { describe, it, expect } from "vitest";
import uniqBy from "lodash/uniqBy";
import { ATTACHMENT_TYPE, ParsedMediaData } from "@/types/attachments";

// Test the deduplication logic used in AttachmentsList component
describe("AttachmentsList deduplication logic", () => {
  it("should deduplicate attachments with the same URL", () => {
    const attachments = [
      {
        name: "video.mp4",
        url: "https://example.com/video.mp4",
        type: ATTACHMENT_TYPE.VIDEO,
      },
    ];

    const media: ParsedMediaData[] = [
      {
        name: "video.mp4",
        url: "https://example.com/video.mp4",
        type: ATTACHMENT_TYPE.VIDEO,
      },
    ];

    const combined = [...attachments, ...media];
    const deduplicated = uniqBy(combined, "url");

    // Should only have one entry for the video URL
    expect(deduplicated).toHaveLength(1);
    expect(deduplicated[0].url).toBe("https://example.com/video.mp4");
  });

  it("should keep first occurrence when deduplicating", () => {
    const attachments = [
      {
        name: "backend-name.mp4",
        url: "https://example.com/video.mp4",
        type: ATTACHMENT_TYPE.VIDEO,
      },
    ];

    const media: ParsedMediaData[] = [
      {
        name: "frontend-name.mp4",
        url: "https://example.com/video.mp4",
        type: ATTACHMENT_TYPE.VIDEO,
      },
    ];

    const combined = [...attachments, ...media];
    const deduplicated = uniqBy(combined, "url");

    // Should keep the first occurrence (from attachments)
    expect(deduplicated).toHaveLength(1);
    expect(deduplicated[0].name).toBe("backend-name.mp4");
  });

  it("should not deduplicate different URLs", () => {
    const attachments = [
      {
        name: "video1.mp4",
        url: "https://example.com/video1.mp4",
        type: ATTACHMENT_TYPE.VIDEO,
      },
    ];

    const media: ParsedMediaData[] = [
      {
        name: "video2.mp4",
        url: "https://example.com/video2.mp4",
        type: ATTACHMENT_TYPE.VIDEO,
      },
    ];

    const combined = [...attachments, ...media];
    const deduplicated = uniqBy(combined, "url");

    // Should have both videos
    expect(deduplicated).toHaveLength(2);
    expect(deduplicated[0].url).toBe("https://example.com/video1.mp4");
    expect(deduplicated[1].url).toBe("https://example.com/video2.mp4");
  });

  it("should handle video URL passed as image in playground", () => {
    // Simulates the bug scenario: video URL added to image field in playground
    const attachments = [
      {
        name: "video.mp4",
        url: "https://cdn.example.com/video.mp4",
        type: ATTACHMENT_TYPE.VIDEO,
      },
    ];

    const media: ParsedMediaData[] = [
      {
        name: "video.mp4",
        url: "https://cdn.example.com/video.mp4",
        type: ATTACHMENT_TYPE.VIDEO,
      },
    ];

    const combined = [...attachments, ...media];
    const deduplicated = uniqBy(combined, "url");

    // Should only show one attachment, not two
    expect(deduplicated).toHaveLength(1);
    expect(deduplicated[0].url).toBe("https://cdn.example.com/video.mp4");
    expect(deduplicated[0].type).toBe(ATTACHMENT_TYPE.VIDEO);
  });

  it("should handle multiple duplicates and unique items", () => {
    const attachments = [
      {
        name: "video1.mp4",
        url: "https://example.com/video1.mp4",
        type: ATTACHMENT_TYPE.VIDEO,
      },
      {
        name: "image1.jpg",
        url: "https://example.com/image1.jpg",
        type: ATTACHMENT_TYPE.IMAGE,
      },
    ];

    const media: ParsedMediaData[] = [
      {
        name: "video1.mp4",
        url: "https://example.com/video1.mp4",
        type: ATTACHMENT_TYPE.VIDEO,
      },
      {
        name: "video2.mp4",
        url: "https://example.com/video2.mp4",
        type: ATTACHMENT_TYPE.VIDEO,
      },
    ];

    const combined = [...attachments, ...media];
    const deduplicated = uniqBy(combined, "url");

    // Should have 3 unique items: video1, image1, video2
    expect(deduplicated).toHaveLength(3);
    expect(deduplicated.find((d) => d.url.includes("video1"))).toBeDefined();
    expect(deduplicated.find((d) => d.url.includes("image1"))).toBeDefined();
    expect(deduplicated.find((d) => d.url.includes("video2"))).toBeDefined();
  });

  it("should handle empty attachments", () => {
    const attachments: typeof media = [];
    const media: ParsedMediaData[] = [
      {
        name: "video.mp4",
        url: "https://example.com/video.mp4",
        type: ATTACHMENT_TYPE.VIDEO,
      },
    ];

    const combined = [...attachments, ...media];
    const deduplicated = uniqBy(combined, "url");

    expect(deduplicated).toHaveLength(1);
    expect(deduplicated[0].url).toBe("https://example.com/video.mp4");
  });

  it("should handle empty media", () => {
    const attachments = [
      {
        name: "video.mp4",
        url: "https://example.com/video.mp4",
        type: ATTACHMENT_TYPE.VIDEO,
      },
    ];
    const media: ParsedMediaData[] = [];

    const combined = [...attachments, ...media];
    const deduplicated = uniqBy(combined, "url");

    expect(deduplicated).toHaveLength(1);
    expect(deduplicated[0].url).toBe("https://example.com/video.mp4");
  });

  it("should handle URLs with query parameters", () => {
    const attachments = [
      {
        name: "video.mp4",
        url: "https://example.com/video.mp4?quality=high&size=large",
        type: ATTACHMENT_TYPE.VIDEO,
      },
    ];

    const media: ParsedMediaData[] = [
      {
        name: "video.mp4",
        url: "https://example.com/video.mp4?quality=high&size=large",
        type: ATTACHMENT_TYPE.VIDEO,
      },
    ];

    const combined = [...attachments, ...media];
    const deduplicated = uniqBy(combined, "url");

    // Should deduplicate even with query parameters
    expect(deduplicated).toHaveLength(1);
    expect(deduplicated[0].url).toBe(
      "https://example.com/video.mp4?quality=high&size=large",
    );
  });

  it("should treat URLs with different query parameters as different", () => {
    const attachments = [
      {
        name: "video.mp4",
        url: "https://example.com/video.mp4?quality=high",
        type: ATTACHMENT_TYPE.VIDEO,
      },
    ];

    const media: ParsedMediaData[] = [
      {
        name: "video.mp4",
        url: "https://example.com/video.mp4?quality=low",
        type: ATTACHMENT_TYPE.VIDEO,
      },
    ];

    const combined = [...attachments, ...media];
    const deduplicated = uniqBy(combined, "url");

    // Different query parameters = different URLs
    expect(deduplicated).toHaveLength(2);
  });
});
