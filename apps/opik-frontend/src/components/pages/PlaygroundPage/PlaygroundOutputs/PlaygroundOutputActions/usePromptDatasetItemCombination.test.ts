import { describe, expect, it } from "vitest";
import { LLMMessage } from "@/types/llm";
import { LLM_MESSAGE_ROLE } from "@/types/llm";
import { transformMessageIntoProviderMessage } from "./usePromptDatasetItemCombination";

describe("transformMessageIntoProviderMessage", () => {
  describe("image URL array expansion", () => {
    it("should expand single image array to one image_url object", () => {
      const message: LLMMessage = {
        id: "msg-1",
        role: LLM_MESSAGE_ROLE.user,
        content: [
          { type: "text", text: "Look at this" },
          { type: "image_url", image_url: { url: "{{images}}" } },
        ],
      };

      const datasetItem = {
        images: ["https://example.com/image1.jpg"],
      };

      const result = transformMessageIntoProviderMessage(message, datasetItem);

      expect(result.content).toHaveLength(2);
      expect(result.content[0]).toEqual({
        type: "text",
        text: "Look at this",
      });
      expect(result.content[1]).toEqual({
        type: "image_url",
        image_url: { url: "https://example.com/image1.jpg" },
      });
    });

    it("should expand multiple images array to multiple image_url objects", () => {
      const message: LLMMessage = {
        id: "msg-1",
        role: LLM_MESSAGE_ROLE.user,
        content: [
          { type: "text", text: "Look at these" },
          { type: "image_url", image_url: { url: "{{images}}" } },
        ],
      };

      const imageUrls = [
        "https://example.com/image1.jpg",
        "https://example.com/image2.jpg",
        "https://example.com/image3.jpg",
      ];

      const datasetItem = { images: imageUrls };

      const result = transformMessageIntoProviderMessage(message, datasetItem);

      expect(result.content).toHaveLength(4);
      expect(result.content[0]).toEqual({
        type: "text",
        text: "Look at these",
      });

      imageUrls.forEach((url, index) => {
        expect(result.content[index + 1]).toEqual({
          type: "image_url",
          image_url: { url },
        });
      });
    });

    it("should handle empty image array by removing image_url object", () => {
      const message: LLMMessage = {
        id: "msg-1",
        role: LLM_MESSAGE_ROLE.user,
        content: [
          { type: "text", text: "No images" },
          { type: "image_url", image_url: { url: "{{images}}" } },
        ],
      };

      const datasetItem = { images: [] };

      const result = transformMessageIntoProviderMessage(message, datasetItem);

      expect(result.content).toHaveLength(1);
      expect(result.content[0]).toEqual({
        type: "text",
        text: "No images",
      });
    });

    it("should preserve literal URLs when not using mustache variables", () => {
      const literalUrl = "https://example.com/static.jpg";
      const message: LLMMessage = {
        id: "msg-1",
        role: LLM_MESSAGE_ROLE.user,
        content: [
          { type: "text", text: "Literal image" },
          { type: "image_url", image_url: { url: literalUrl } },
        ],
      };

      const result = transformMessageIntoProviderMessage(message, {});

      expect(result.content).toHaveLength(2);
      expect(result.content[1]).toEqual({
        type: "image_url",
        image_url: { url: literalUrl },
      });
    });
  });

  describe("video URL array expansion", () => {
    it("should expand multiple videos array to multiple video_url objects", () => {
      const message: LLMMessage = {
        id: "msg-1",
        role: LLM_MESSAGE_ROLE.user,
        content: [
          { type: "text", text: "Watch these" },
          { type: "video_url", video_url: { url: "{{videos}}" } },
        ],
      };

      const videoUrls = [
        "https://example.com/video1.mp4",
        "https://example.com/video2.mp4",
      ];

      const datasetItem = { videos: videoUrls };

      const result = transformMessageIntoProviderMessage(message, datasetItem);

      expect(result.content).toHaveLength(3);
      videoUrls.forEach((url, index) => {
        expect(result.content[index + 1]).toEqual({
          type: "video_url",
          video_url: { url },
        });
      });
    });

    it("should handle empty video array", () => {
      const message: LLMMessage = {
        id: "msg-1",
        role: LLM_MESSAGE_ROLE.user,
        content: [
          { type: "text", text: "No videos" },
          { type: "video_url", video_url: { url: "{{videos}}" } },
        ],
      };

      const datasetItem = { videos: [] };

      const result = transformMessageIntoProviderMessage(message, datasetItem);

      expect(result.content).toHaveLength(1);
      expect(result.content[0]).toEqual({
        type: "text",
        text: "No videos",
      });
    });
  });

  describe("audio URL array expansion", () => {
    it("should expand multiple audios array to multiple audio_url objects", () => {
      const message: LLMMessage = {
        id: "msg-1",
        role: LLM_MESSAGE_ROLE.user,
        content: [
          { type: "text", text: "Listen to these" },
          { type: "audio_url", audio_url: { url: "{{audios}}" } },
        ],
      };

      const audioUrls = [
        "https://example.com/audio1.mp3",
        "https://example.com/audio2.mp3",
      ];

      const datasetItem = { audios: audioUrls };

      const result = transformMessageIntoProviderMessage(message, datasetItem);

      expect(result.content).toHaveLength(3);
      audioUrls.forEach((url, index) => {
        expect(result.content[index + 1]).toEqual({
          type: "audio_url",
          audio_url: { url },
        });
      });
    });

    it("should handle empty audio array", () => {
      const message: LLMMessage = {
        id: "msg-1",
        role: LLM_MESSAGE_ROLE.user,
        content: [
          { type: "text", text: "No audio" },
          { type: "audio_url", audio_url: { url: "{{audios}}" } },
        ],
      };

      const datasetItem = { audios: [] };

      const result = transformMessageIntoProviderMessage(message, datasetItem);

      expect(result.content).toHaveLength(1);
    });
  });

  describe("mixed media types", () => {
    it("should expand multiple image and video arrays in same message", () => {
      const message: LLMMessage = {
        id: "msg-1",
        role: LLM_MESSAGE_ROLE.user,
        content: [
          { type: "text", text: "Mixed content" },
          { type: "image_url", image_url: { url: "{{images}}" } },
          { type: "video_url", video_url: { url: "{{videos}}" } },
        ],
      };

      const datasetItem = {
        images: [
          "https://example.com/image1.jpg",
          "https://example.com/image2.jpg",
        ],
        videos: ["https://example.com/video1.mp4"],
      };

      const result = transformMessageIntoProviderMessage(message, datasetItem);

      expect(result.content).toHaveLength(4);
      expect(result.content[0]).toEqual({ type: "text", text: "Mixed content" });
      expect(result.content[1]).toEqual({
        type: "image_url",
        image_url: { url: "https://example.com/image1.jpg" },
      });
      expect(result.content[2]).toEqual({
        type: "image_url",
        image_url: { url: "https://example.com/image2.jpg" },
      });
      expect(result.content[3]).toEqual({
        type: "video_url",
        video_url: { url: "https://example.com/video1.mp4" },
      });
    });

    it("should filter out empty arrays while keeping non-empty media", () => {
      const message: LLMMessage = {
        id: "msg-1",
        role: LLM_MESSAGE_ROLE.user,
        content: [
          { type: "text", text: "Mixed" },
          { type: "image_url", image_url: { url: "{{images}}" } },
          { type: "video_url", video_url: { url: "{{videos}}" } },
          { type: "audio_url", audio_url: { url: "{{audios}}" } },
        ],
      };

      const datasetItem = {
        images: ["https://example.com/image1.jpg"],
        videos: [],
        audios: [],
      };

      const result = transformMessageIntoProviderMessage(message, datasetItem);

      expect(result.content).toHaveLength(2);
      expect(result.content[1]).toEqual({
        type: "image_url",
        image_url: { url: "https://example.com/image1.jpg" },
      });
    });

    it("should handle all empty media arrays", () => {
      const message: LLMMessage = {
        id: "msg-1",
        role: LLM_MESSAGE_ROLE.user,
        content: [
          { type: "text", text: "Text only" },
          { type: "image_url", image_url: { url: "{{images}}" } },
          { type: "video_url", video_url: { url: "{{videos}}" } },
          { type: "audio_url", audio_url: { url: "{{audios}}" } },
        ],
      };

      const datasetItem = {
        images: [],
        videos: [],
        audios: [],
      };

      const result = transformMessageIntoProviderMessage(message, datasetItem);

      expect(result.content).toHaveLength(1);
      expect(result.content[0]).toEqual({
        type: "text",
        text: "Text only",
      });
    });
  });

  describe("text-only messages", () => {
    it("should render string content with mustache variables", () => {
      const message: LLMMessage = {
        id: "msg-1",
        role: LLM_MESSAGE_ROLE.user,
        content: "Hello {{name}}, your age is {{age}}",
      };

      const datasetItem = {
        name: "John",
        age: 30,
      };

      const result = transformMessageIntoProviderMessage(message, datasetItem);

      expect(result.content).toBe("Hello John, your age is 30");
    });

    it("should preserve string content without variables", () => {
      const message: LLMMessage = {
        id: "msg-1",
        role: LLM_MESSAGE_ROLE.user,
        content: "Plain text message",
      };

      const result = transformMessageIntoProviderMessage(message, {});

      expect(result.content).toBe("Plain text message");
    });
  });

  describe("edge cases", () => {
    it("should handle base64 encoded image data URIs", () => {
      const base64DataUri =
        "data:image/jpeg;base64,/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAIBAQI";
      const message: LLMMessage = {
        id: "msg-1",
        role: LLM_MESSAGE_ROLE.user,
        content: [
          { type: "text", text: "Base64 image" },
          { type: "image_url", image_url: { url: "{{images}}" } },
        ],
      };

      const datasetItem = { images: [base64DataUri] };

      const result = transformMessageIntoProviderMessage(message, datasetItem);

      expect(result.content[1]).toEqual({
        type: "image_url",
        image_url: { url: base64DataUri },
      });
    });

    it("should handle no text content with media arrays", () => {
      const message: LLMMessage = {
        id: "msg-1",
        role: LLM_MESSAGE_ROLE.user,
        content: [{ type: "image_url", image_url: { url: "{{images}}" } }],
      };

      const datasetItem = {
        images: ["https://example.com/image1.jpg"],
      };

      const result = transformMessageIntoProviderMessage(message, datasetItem);

      expect(result.content).toHaveLength(1);
      expect(result.content[0]).toEqual({
        type: "image_url",
        image_url: { url: "https://example.com/image1.jpg" },
      });
    });

    it("should preserve role in response", () => {
      const message: LLMMessage = {
        id: "msg-1",
        role: LLM_MESSAGE_ROLE.assistant,
        content: "Assistant response",
      };

      const result = transformMessageIntoProviderMessage(message, {});

      expect(result.role).toBe(LLM_MESSAGE_ROLE.assistant);
    });
  });
});
