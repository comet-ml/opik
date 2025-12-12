import { describe, it, expect, beforeEach, vi } from "vitest";
import {
  extractAllHttpUrls,
  detectMediaTypeFromUrl,
  detectAdditionalMedia,
  clearMediaTypeCache,
  getMediaTypeCacheSize,
  isHttpUrl,
} from "@/lib/media";
import { ATTACHMENT_TYPE, ParsedMediaData } from "@/types/attachments";

// Mock fetch globally
global.fetch = vi.fn();

describe("media utilities", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    clearMediaTypeCache();
  });

  describe("isHttpUrl", () => {
    it("should return true for valid HTTP URLs", () => {
      expect(isHttpUrl("http://example.com")).toBe(true);
      expect(isHttpUrl("https://example.com")).toBe(true);
      expect(isHttpUrl("https://example.com/path?query=1")).toBe(true);
    });

    it("should return false for invalid URLs", () => {
      expect(isHttpUrl("ftp://example.com")).toBe(false);
      expect(isHttpUrl("data:image/png;base64,abc")).toBe(false);
      expect(isHttpUrl("not a url")).toBe(false);
      expect(isHttpUrl("")).toBe(false);
    });
  });

  describe("extractAllHttpUrls", () => {
    it("should extract single HTTP URL", () => {
      const input = "Check out https://example.com/image";
      const urls = extractAllHttpUrls(input);
      expect(urls).toEqual(["https://example.com/image"]);
    });

    it("should extract multiple HTTP URLs", () => {
      const input = `
        Image: https://example.com/image.jpg
        Video: https://example.com/video.mp4
        Other: http://test.com/audio.mp3
      `;
      const urls = extractAllHttpUrls(input);
      expect(urls).toEqual([
        "https://example.com/image.jpg",
        "https://example.com/video.mp4",
        "http://test.com/audio.mp3",
      ]);
    });

    it("should deduplicate URLs", () => {
      const input = `
        https://example.com/image.jpg
        https://example.com/image.jpg
        https://example.com/video.mp4
      `;
      const urls = extractAllHttpUrls(input);
      expect(urls).toEqual([
        "https://example.com/image.jpg",
        "https://example.com/video.mp4",
      ]);
    });

    it("should extract URLs without file extensions", () => {
      const input = "Snap video: https://cf-st.sc-cdn.net/d/abc123";
      const urls = extractAllHttpUrls(input);
      expect(urls).toEqual(["https://cf-st.sc-cdn.net/d/abc123"]);
    });

    it("should return empty array when no URLs found", () => {
      const input = "No URLs here, just plain text";
      const urls = extractAllHttpUrls(input);
      expect(urls).toEqual([]);
    });

    it("should extract URLs from JSON string", () => {
      const input = JSON.stringify({
        url: "https://example.com/video",
        nested: { link: "https://test.com/audio" },
      });
      const urls = extractAllHttpUrls(input);
      expect(urls).toEqual([
        "https://example.com/video",
        "https://test.com/audio",
      ]);
    });
  });

  describe("detectMediaTypeFromUrl", () => {
    it("should cache results to avoid duplicate requests", async () => {
      const testUrl = "https://example.com/video";

      (global.fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
        ok: true,
        headers: {
          get: () => "video/mp4",
        },
      });

      // First call
      const result1 = await detectMediaTypeFromUrl(testUrl);
      expect(result1).toEqual({ category: "video", mimeType: "video/mp4" });
      expect(getMediaTypeCacheSize()).toBe(1);

      // Second call should use cache
      const result2 = await detectMediaTypeFromUrl(testUrl);
      expect(result2).toEqual({ category: "video", mimeType: "video/mp4" });

      // Fetch should only be called once
      expect(global.fetch).toHaveBeenCalledTimes(1);
    });

    it("should cache null results for failed requests", async () => {
      const testUrl = "https://example.com/404";

      // Mock HEAD request failure (ok: false)
      (global.fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
        ok: false,
      });
      // Mock GET fallback also failing
      (global.fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
        ok: false,
      });

      // First call
      const result1 = await detectMediaTypeFromUrl(testUrl);
      expect(result1).toBeNull();
      expect(getMediaTypeCacheSize()).toBe(1);

      // Second call should use cache
      const result2 = await detectMediaTypeFromUrl(testUrl);
      expect(result2).toBeNull();

      // Fetch should be called twice (HEAD + GET fallback) on first call, then cached
      expect(global.fetch).toHaveBeenCalledTimes(2);
    });

    it("should handle timeout errors", async () => {
      const testUrl = "https://slow.example.com/video";

      (global.fetch as ReturnType<typeof vi.fn>).mockRejectedValueOnce(
        new Error("Timeout"),
      );

      const result = await detectMediaTypeFromUrl(testUrl);
      expect(result).toBeNull();
      expect(getMediaTypeCacheSize()).toBe(1);
    });

    it("should not retry GET for 403 Forbidden responses", async () => {
      const testUrl = "https://forbidden.example.com/video";

      // Mock HEAD request returning 403
      (global.fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
        ok: false,
        status: 403,
      });

      const result = await detectMediaTypeFromUrl(testUrl);
      expect(result).toBeNull();
      expect(getMediaTypeCacheSize()).toBe(1);

      // Should only call HEAD, not GET (security fix)
      expect(global.fetch).toHaveBeenCalledTimes(1);
    });

    it("should not retry GET for 404 Not Found responses", async () => {
      const testUrl = "https://notfound.example.com/video";

      // Mock HEAD request returning 404
      (global.fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
        ok: false,
        status: 404,
      });

      const result = await detectMediaTypeFromUrl(testUrl);
      expect(result).toBeNull();

      // Should only call HEAD, not GET
      expect(global.fetch).toHaveBeenCalledTimes(1);
    });
  });

  describe("cache management", () => {
    it("should clear cache", async () => {
      (global.fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
        ok: true,
        headers: {
          get: () => "video/mp4",
        },
      });

      await detectMediaTypeFromUrl("https://example.com/video");
      expect(getMediaTypeCacheSize()).toBe(1);

      clearMediaTypeCache();
      expect(getMediaTypeCacheSize()).toBe(0);
    });

    it("should track cache size correctly", async () => {
      (global.fetch as ReturnType<typeof vi.fn>)
        .mockResolvedValueOnce({
          ok: true,
          headers: { get: () => "video/mp4" },
        })
        .mockResolvedValueOnce({
          ok: true,
          headers: { get: () => "image/png" },
        })
        .mockResolvedValueOnce({
          ok: true,
          headers: { get: () => "audio/mpeg" },
        });

      expect(getMediaTypeCacheSize()).toBe(0);

      await detectMediaTypeFromUrl("https://example.com/video");
      expect(getMediaTypeCacheSize()).toBe(1);

      await detectMediaTypeFromUrl("https://example.com/image");
      expect(getMediaTypeCacheSize()).toBe(2);

      await detectMediaTypeFromUrl("https://example.com/audio");
      expect(getMediaTypeCacheSize()).toBe(3);
    });
  });

  describe("detectAdditionalMedia", () => {
    it("should detect URLs without extensions and add to existing media", async () => {
      const snapVideo = "https://cf-st.sc-cdn.net/d/abc123";
      const regularImage = "https://example.com/photo.jpg";

      // Mock HEAD request for Snap video
      (global.fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
        ok: true,
        headers: {
          get: () => "video/mp4",
        },
      });

      const input = {
        text: `Check out ${regularImage} and ${snapVideo}`,
      };

      // Existing media from processInputData (only detected regular image)
      const existingMedia = [
        {
          url: regularImage,
          name: "photo.jpg",
          type: ATTACHMENT_TYPE.IMAGE as const,
        },
      ];

      const result = await detectAdditionalMedia(input, existingMedia);

      // Should now have both image and video
      expect(result).toHaveLength(2);
      expect(result.find((m) => m.url === regularImage)).toBeDefined();
      expect(result.find((m) => m.url === snapVideo)).toBeDefined();
      expect(result.find((m) => m.url === snapVideo)?.type).toBe(
        ATTACHMENT_TYPE.VIDEO,
      );
    });

    it("should not duplicate already detected URLs", async () => {
      const videoUrl = "https://example.com/video.mp4";

      const input = {
        text: `Video: ${videoUrl}`,
      };

      // Already detected by processInputData
      const existingMedia = [
        {
          url: videoUrl,
          name: "video.mp4",
          type: ATTACHMENT_TYPE.VIDEO as const,
        },
      ];

      const result = await detectAdditionalMedia(input, existingMedia);

      // Should not make HEAD request or add duplicate
      expect(result).toHaveLength(1);
      expect(result[0].url).toBe(videoUrl);
      expect(global.fetch).not.toHaveBeenCalled();
    });

    it("should return existing media when input is undefined", async () => {
      const existingMedia = [
        {
          url: "https://example.com/img.jpg",
          name: "img.jpg",
          type: ATTACHMENT_TYPE.IMAGE as const,
        },
      ];

      const result = await detectAdditionalMedia(undefined, existingMedia);

      expect(result).toEqual(existingMedia);
    });

    it("should detect multiple media types via HEAD requests", async () => {
      const video1 = "https://cf-st.sc-cdn.net/d/video1";
      const image1 = "https://example.com/img/photo";
      const audio1 = "https://example.com/sound/file";

      // Mock HEAD requests
      (global.fetch as ReturnType<typeof vi.fn>)
        .mockResolvedValueOnce({
          ok: true,
          headers: { get: () => "video/mp4" },
        })
        .mockResolvedValueOnce({
          ok: true,
          headers: { get: () => "image/jpeg" },
        })
        .mockResolvedValueOnce({
          ok: true,
          headers: { get: () => "audio/mpeg" },
        });

      const input = {
        text: `Media: ${video1}, ${image1}, ${audio1}`,
      };

      const existingMedia: ParsedMediaData[] = [];

      const result = await detectAdditionalMedia(input, existingMedia);

      expect(result).toHaveLength(3);
      expect(
        result.find((m) => m.type === ATTACHMENT_TYPE.VIDEO),
      ).toBeDefined();
      expect(
        result.find((m) => m.type === ATTACHMENT_TYPE.IMAGE),
      ).toBeDefined();
      expect(
        result.find((m) => m.type === ATTACHMENT_TYPE.AUDIO),
      ).toBeDefined();
    });
  });
});
