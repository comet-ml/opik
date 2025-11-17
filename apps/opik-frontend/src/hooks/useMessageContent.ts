import { useCallback } from "react";
import { MessageContent, TextPart, ImagePart, VideoPart } from "@/types/llm";
import { parseLLMMessageContent } from "@/lib/llm";

interface UseMessageContentProps {
  content: MessageContent;
  onChangeContent: (newContent: MessageContent) => void;
}

interface UseMessageContentReturn {
  localText: string;
  images: string[];
  videos: string[];
  setImages: (newImages: string[]) => void;
  setVideos: (newVideos: string[]) => void;
  handleContentChange: (newText: string) => void;
}

export const useMessageContent = ({
  content,
  onChangeContent,
}: UseMessageContentProps): UseMessageContentReturn => {
  // Parse content directly from prop - no state
  const { text, images, videos } = parseLLMMessageContent(content);

  // Helper to rebuild MessageContent from parts
  const buildMessageContent = useCallback(
    (
      newText: string,
      newImages: string[],
      newVideos: string[],
    ): MessageContent => {
      if (newImages.length === 0 && newVideos.length === 0) {
        return newText;
      }

      const parts: Array<TextPart | ImagePart | VideoPart> = [];
      if (newText.trim()) {
        parts.push({ type: "text", text: newText });
      }
      newImages.forEach((url) => {
        parts.push({ type: "image_url", image_url: { url } });
      });
      newVideos.forEach((url) => {
        parts.push({ type: "video_url", video_url: { url } });
      });
      return parts;
    },
    [],
  );

  // Handler for text changes
  const handleContentChange = useCallback(
    (newText: string) => {
      const newContent = buildMessageContent(newText, images, videos);
      onChangeContent(newContent);
    },
    [images, videos, buildMessageContent, onChangeContent],
  );

  // Handler for images changes
  const handleSetImages = useCallback(
    (newImages: string[]) => {
      const newContent = buildMessageContent(text, newImages, videos);
      onChangeContent(newContent);
    },
    [text, videos, buildMessageContent, onChangeContent],
  );

  // Handler for videos changes
  const handleSetVideos = useCallback(
    (newVideos: string[]) => {
      const newContent = buildMessageContent(text, images, newVideos);
      onChangeContent(newContent);
    },
    [text, images, buildMessageContent, onChangeContent],
  );

  return {
    localText: text,
    images,
    videos,
    setImages: handleSetImages,
    setVideos: handleSetVideos,
    handleContentChange,
  };
};
