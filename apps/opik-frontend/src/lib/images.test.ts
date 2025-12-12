import { describe, it, expect, vi, beforeEach } from "vitest";
import * as utils from "@/lib/utils";
import {
  isImageBase64String,
  isImageContent,
  processInputData,
} from "@/lib/images";
import { ATTACHMENT_TYPE } from "@/types/attachments";

// Mock dependencies
vi.mock("@/lib/utils", () => ({
  safelyParseJSON: vi.fn((input) => {
    try {
      return JSON.parse(input);
    } catch (e) {
      return undefined;
    }
  }),
}));

describe("processInputData", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("should return empty result when input is undefined", () => {
    const result = processInputData(undefined);
    expect(result).toEqual({
      media: [],
      formattedData: undefined,
    });
  });

  it("should return empty images array when input has no images", () => {
    const input = { text: "Hello world" };
    const result = processInputData(input);
    expect(result.media).toEqual([]);
    expect(result.formattedData).toEqual(input);
  });

  it("should extract OpenAI image URLs from messages", () => {
    const input = {
      messages: [
        {
          content: [
            { type: "text", text: "Hello" },
            {
              type: "image_url",
              image_url: { url: "https://example.com/image.jpg" },
            },
          ],
        },
      ],
    };

    const result = processInputData(input);
    expect(result.media).toHaveLength(1);
    expect(result.media[0]).toEqual({
      url: "https://example.com/image.jpg",
      name: "image.jpg",
      type: ATTACHMENT_TYPE.IMAGE,
    });
  });

  it("should extract data URI images", () => {
    const dataURI = "data:image/png;base64,iVBORw0KGgo";
    const input = { text: `Check this image: ${dataURI}` };

    const result = processInputData(input);
    expect(result.media).toHaveLength(1);
    expect(result.media[0].url).toBe(dataURI);
    expect(result.media[0].name).toBe("Base64: [image_0]");
    expect(result.media[0].type).toBe(ATTACHMENT_TYPE.IMAGE);
    expect(result.formattedData).toEqual({
      text: "Check this image: [image_0]",
    });
  });

  it("should extract prefixed base64 images", () => {
    const base64Image = "iVBORw0KGgoAAAA"; // PNG prefix
    const input = { text: `Raw base64: ${base64Image}` };

    const result = processInputData(input);
    expect(result.media).toHaveLength(1);
    expect(result.media[0].url).toBe(`data:image/png;base64,${base64Image}`);
    expect(result.media[0].name).toBe("Base64: [image_0]");
  });

  it("should extract image URLs from text", () => {
    const input = {
      text: 'Images: "https://example.com/image1.jpg" and "https://example.com/image2.png?size=large"',
    };

    const result = processInputData(input);
    expect(result.media).toHaveLength(2);
    expect(result.media[0].url).toBe("https://example.com/image1.jpg");
    expect(result.media[1].url).toBe(
      "https://example.com/image2.png?size=large",
    );
  });

  it("should deduplicate images with the same URL", () => {
    const input = {
      messages: [
        {
          content: [
            {
              type: "image_url",
              image_url: { url: "https://example.com/image.jpg" },
            },
            {
              type: "image_url",
              image_url: { url: "https://example.com/image.jpg" }, // Duplicate URL
            },
          ],
        },
      ],
      text: 'Look at "https://example.com/image.jpg"', // Same URL again
    };

    const result = processInputData(input);
    expect(result.media).toHaveLength(1);
    expect(result.media[0].url).toBe("https://example.com/image.jpg");
  });

  it("should handle multiple types of images in one input", () => {
    const dataURI = "data:image/jpeg;base64,/9j/";
    const base64Image = "iVBORw0KGgoAAAA"; // PNG prefix

    const input = {
      messages: [
        {
          content: [
            { type: "text", text: "Hello" },
            {
              type: "image_url",
              image_url: { url: "https://example.com/photo.jpg" },
            },
          ],
        },
      ],
      text: `Image1: ${dataURI}, Image2: ${base64Image}, URL: "https://images.com/pic.png"`,
    };

    const result = processInputData(input);
    expect(result.media).toHaveLength(4);

    // Check that we have one of each type
    expect(
      result.media.some((img) => img.url === "https://example.com/photo.jpg"),
    ).toBeTruthy();
    expect(result.media.some((img) => img.url === dataURI)).toBeTruthy();
    expect(
      result.media.some((img) =>
        img.url.includes("data:image/png;base64,iVBORw0KGgoAAAA"),
      ),
    ).toBeTruthy();
    expect(
      result.media.some((img) => img.url === "https://images.com/pic.png"),
    ).toBeTruthy();
  });

  it("should skip non-image OpenAI content", () => {
    const input = {
      messages: [
        {
          content: [
            { type: "text", text: "Just text" },
            { type: "other_type", data: "Not an image" },
          ],
        },
      ],
    };

    const result = processInputData(input);
    expect(result.media).toHaveLength(0);
  });

  it("should handle malformed image_url content", () => {
    const input = {
      messages: [
        {
          content: [
            { type: "image_url", image_url: {} }, // Missing URL
            { type: "image_url" }, // Missing image_url object
            { type: "image_url", image_url: { url: 123 } }, // URL is not a string
          ],
        },
      ],
    };

    const result = processInputData(input);
    expect(result.media).toHaveLength(0);
  });

  it("should properly extract images with URL parameters and fragments", () => {
    const input = {
      text: 'Check "https://example.com/image.jpg?width=800&height=600#section1"',
    };

    const result = processInputData(input);
    expect(result.media).toHaveLength(1);
    expect(result.media[0].url).toBe(
      "https://example.com/image.jpg?width=800&height=600#section1",
    );
    expect(result.media[0].name).toBe("image.jpg");
  });

  it("should extract image names correctly from URLs", () => {
    const input = {
      messages: [
        {
          content: [
            {
              type: "image_url",
              image_url: {
                url: "https://example.com/path/to/my-awesome-image.jpg",
              },
            },
          ],
        },
      ],
    };

    const result = processInputData(input);
    expect(result.media).toHaveLength(1);
    expect(result.media[0].name).toBe("my-awesome-image.jpg");
  });

  it("should handle base64 images with different prefixes", () => {
    // Test different prefixes from BASE64_PREFIXES_MAP

    const prefixes = {
      "/9j/4AAQSkZJRgABAQAAAQABAAD/": "jpeg", // minimal JPEG
      "R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7": "gif", // minimal GIF
      "Qk1GAwAAAAgAAAA//8AAwAAA": "bmp", // minimal BMP
      "SUkqAGkAAAD/AAEAAAABAAEAAAABAAEAAAD/": "tiff", // minimal TIFF
      "UklGRgAAAABXRU5ErkJggg==": "webp", // minimal WebP
    };

    for (const [prefix, format] of Object.entries(prefixes)) {
      const input = { text: `Image: ${prefix}` };
      const result = processInputData(input);

      expect(result.media).toHaveLength(1);
      expect(result.media[0].url).toBe(`data:image/${format};base64,${prefix}`);
      expect(result.media[0].name).toMatch(/Base64: \[image_\d+\]/);
    }
  });

  it("should handle image URLs with all supported extensions", () => {
    // Sample of supported extensions from IMAGE_URL_EXTENSIONS
    const extensions = ["jpg", "png", "gif", "webp", "svg", "avif", "heic"];

    const urls = extensions.map((ext) => `"https://example.com/image.${ext}"`);
    const input = { text: `Images: ${urls.join(" ")}` };

    const result = processInputData(input);

    expect(result.media).toHaveLength(extensions.length);

    extensions.forEach((ext, idx) => {
      expect(result.media[idx].url).toBe(`https://example.com/image.${ext}`);
      expect(result.media[idx].name).toBe(`image.${ext}`);
    });
  });

  it("should call safelyParseJSON with updated input string", () => {
    const dataURI = "data:image/png;base64,iVBORw0KGgo...";
    const input = { text: `Image: ${dataURI}` };

    processInputData(input);

    expect(utils.safelyParseJSON).toHaveBeenCalled();
    // The input to safelyParseJSON should have the image replaced with [image_0]
    const parsedArg = vi.mocked(utils.safelyParseJSON).mock.calls[0][0];
    expect(parsedArg).toContain("[image_0]");
    expect(parsedArg).not.toContain(dataURI);
  });

  it("should handle empty messages array", () => {
    const input = { messages: [] };
    const result = processInputData(input);
    expect(result.media).toHaveLength(0);
  });

  it("should handle null or undefined content in messages", () => {
    const input = {
      messages: [
        { content: null },
        { content: undefined },
        {}, // No content property
      ],
    };

    const result = processInputData(input);
    expect(result.media).toHaveLength(0);
  });
});

// Helper function tests
describe("Helper functions", () => {
  it("isImageContent should validate image content correctly", () => {
    expect(
      isImageContent({
        type: "image_url",
        image_url: { url: "https://example.com/img.jpg" },
      }),
    ).toBe(true);
    expect(isImageContent({ type: "image_url", image_url: {} } as never)).toBe(
      false,
    );
    expect(isImageContent({ type: "image_url" })).toBe(false);
    expect(
      isImageContent({ type: "text", text: "Not an image" } as never),
    ).toBe(false);
    expect(isImageContent(undefined)).toBe(false);
    expect(isImageContent(null as never)).toBe(false);
  });

  it("isImageBase64String should identify base64 images correctly", () => {
    expect(isImageBase64String("data:image/png;base64,abc")).toBe(true);
    expect(isImageBase64String("iVBORw0KGgo")).toBe(true); // PNG prefix
    expect(isImageBase64String("/9j/abc")).toBe(true); // JPEG prefix
    expect(isImageBase64String("R0lGODlh")).toBe(true); // GIF prefix
    expect(isImageBase64String("Not a base64 image")).toBe(false);
    expect(isImageBase64String("")).toBe(false);
    expect(isImageBase64String(undefined)).toBe(false);
    expect(isImageBase64String(123)).toBe(false);
  });
});

describe("Duplicate media detection", () => {
  it("should skip video URL in image_url field", () => {
    const input = {
      messages: [
        {
          content: [
            {
              type: "image_url",
              image_url: {
                url: "https://cdn.pixabay.com/video/2024/01/15/video.mp4",
              },
            },
          ],
        },
      ],
    };

    const result = processInputData(input);

    // Video URL should be extracted as video (not image)
    expect(result.media).toHaveLength(1);
    expect(result.media[0].url).toBe(
      "https://cdn.pixabay.com/video/2024/01/15/video.mp4",
    );
    expect(result.media[0].type).toBe(ATTACHMENT_TYPE.VIDEO);
  });

  it("should skip image URL in video_url field", () => {
    const input = {
      messages: [
        {
          content: [
            {
              type: "video_url",
              video_url: {
                url: "https://example.com/image.jpg",
              },
            },
          ],
        },
      ],
    };

    const result = processInputData(input);

    // Image URL should be extracted as image (not video)
    expect(result.media).toHaveLength(1);
    expect(result.media[0].url).toBe("https://example.com/image.jpg");
    expect(result.media[0].type).toBe(ATTACHMENT_TYPE.IMAGE);
  });

  it("should keep extensionless URLs in image_url as images", () => {
    const input = {
      messages: [
        {
          content: [
            {
              type: "image_url",
              image_url: {
                url: "https://api.example.com/media/12345",
              },
            },
          ],
        },
      ],
    };

    const result = processInputData(input);

    // Extensionless URL should be kept as image based on field type
    expect(result.media).toHaveLength(1);
    expect(result.media[0].url).toBe("https://api.example.com/media/12345");
    expect(result.media[0].type).toBe(ATTACHMENT_TYPE.IMAGE);
  });

  it("should keep extensionless URLs in video_url as videos", () => {
    const input = {
      messages: [
        {
          content: [
            {
              type: "video_url",
              video_url: {
                url: "https://api.example.com/media/12345",
              },
            },
          ],
        },
      ],
    };

    const result = processInputData(input);

    // Extensionless URL should be kept as video based on field type
    expect(result.media).toHaveLength(1);
    expect(result.media[0].url).toBe("https://api.example.com/media/12345");
    expect(result.media[0].type).toBe(ATTACHMENT_TYPE.VIDEO);
  });

  it("should handle mix of correct and incorrect extension types", () => {
    const input = {
      messages: [
        {
          content: [
            {
              type: "image_url",
              image_url: { url: "https://example.com/photo.jpg" }, // correct
            },
            {
              type: "image_url",
              image_url: { url: "https://example.com/video.mp4" }, // wrong ext
            },
            {
              type: "video_url",
              video_url: { url: "https://example.com/clip.webm" }, // correct
            },
            {
              type: "video_url",
              video_url: { url: "https://example.com/image.png" }, // wrong ext
            },
          ],
        },
      ],
    };

    const result = processInputData(input);

    // All 4 items should be extracted with their correct types
    expect(result.media).toHaveLength(4);

    const images = result.media.filter((m) => m.type === ATTACHMENT_TYPE.IMAGE);
    const videos = result.media.filter((m) => m.type === ATTACHMENT_TYPE.VIDEO);

    expect(images).toHaveLength(2);
    expect(images.some((m) => m.url === "https://example.com/photo.jpg")).toBe(
      true,
    );
    expect(images.some((m) => m.url === "https://example.com/image.png")).toBe(
      true,
    );

    expect(videos).toHaveLength(2);
    expect(videos.some((m) => m.url === "https://example.com/clip.webm")).toBe(
      true,
    );
    expect(videos.some((m) => m.url === "https://example.com/video.mp4")).toBe(
      true,
    );
  });

  it("should remove cross-duplicates and prioritize video type", () => {
    const input = {
      messages: [
        {
          content: [
            {
              type: "image_url",
              image_url: { url: "https://example.com/media.mp4" },
            },
          ],
        },
      ],
      text: 'Check this video: "https://example.com/media.mp4"',
    };

    const result = processInputData(input);

    // URL with .mp4 should be deduplicated and appear as video
    expect(result.media).toHaveLength(1);
    expect(result.media[0].url).toBe("https://example.com/media.mp4");
    expect(result.media[0].type).toBe(ATTACHMENT_TYPE.VIDEO);
  });

  it("should handle video URLs with query parameters in image_url field", () => {
    const input = {
      messages: [
        {
          content: [
            {
              type: "image_url",
              image_url: {
                url: "https://cdn.example.com/video.mp4?quality=high&size=large",
              },
            },
          ],
        },
      ],
    };

    const result = processInputData(input);

    // Video URL with query params should be extracted as video
    expect(result.media).toHaveLength(1);
    expect(result.media[0].url).toBe(
      "https://cdn.example.com/video.mp4?quality=high&size=large",
    );
    expect(result.media[0].type).toBe(ATTACHMENT_TYPE.VIDEO);
  });

  it("should handle base64 videos correctly in video_url field", () => {
    const base64Video = "data:video/mp4;base64,AAAAIGZ0eXBpc29t";
    const input = {
      messages: [
        {
          content: [
            {
              type: "video_url",
              video_url: {
                url: base64Video,
              },
            },
          ],
        },
      ],
    };

    const result = processInputData(input);

    expect(result.media).toHaveLength(1);
    expect(result.media[0].url).toBe(base64Video);
    expect(result.media[0].name).toContain("Base64 Video");
    expect(result.media[0].type).toBe(ATTACHMENT_TYPE.VIDEO);
  });

  it("should extract base64 images even from video_url field", () => {
    // Base64 URLs don't have extensions to check, so they bypass validation
    // and get extracted by both video and image regex patterns
    const base64Image = "data:image/png;base64,iVBORw0KGgoAAAA";
    const input = {
      messages: [
        {
          content: [
            {
              type: "video_url",
              video_url: {
                url: base64Image,
              },
            },
          ],
        },
      ],
    };

    const result = processInputData(input);

    // Base64 image URL should be deduplicated and extracted as image
    expect(result.media).toHaveLength(1);
    expect(result.media[0].url).toBe(base64Image);
    expect(result.media[0].type).toBe(ATTACHMENT_TYPE.IMAGE);
  });
});
