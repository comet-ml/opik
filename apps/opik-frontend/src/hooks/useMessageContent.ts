import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { MessageContent, TextPart, ImagePart } from "@/types/llm";
import { OnChangeFn } from "@/types/shared";
import { parseLLMMessageContent } from "@/lib/llm";

interface UseMessageContentProps {
  content: MessageContent;
  onChangeContent: (newContent: MessageContent) => void;
}

interface UseMessageContentReturn {
  localText: string;
  images: string[];
  setImages: OnChangeFn<string[]>;
  handleContentChange: (newText: string) => void;
}

export const useMessageContent = ({
  content,
  onChangeContent,
}: UseMessageContentProps): UseMessageContentReturn => {
  // Parse content (string or array)
  const { text: textContent, images: imageUrls } = useMemo(
    () => parseLLMMessageContent(content),
    [content],
  );

  const [localText, setLocalText] = useState(textContent);
  const [images, setImages] = useState<string[]>(imageUrls);
  const lastProcessedContentRef = useRef<MessageContent>(content);

  // Sync external changes to local state
  useEffect(() => {
    setLocalText(textContent);
    setImages(imageUrls);
  }, [textContent, imageUrls]);

  // Rebuild content when text or images change
  useEffect(() => {
    let newContent: MessageContent;

    if (images.length === 0) {
      // Text-only: use plain string
      newContent = localText;
    } else {
      // With images: use array format
      const parts: Array<TextPart | ImagePart> = [];
      if (localText.trim()) {
        parts.push({ type: "text", text: localText });
      }
      images.forEach((url) => {
        parts.push({ type: "image_url", image_url: { url } });
      });
      newContent = parts;
    }

    // Only update if content actually changed
    if (
      JSON.stringify(newContent) !==
      JSON.stringify(lastProcessedContentRef.current)
    ) {
      lastProcessedContentRef.current = newContent;
      onChangeContent(newContent);
    }
  }, [localText, images, onChangeContent]);

  const handleContentChange = useCallback((newText: string) => {
    setLocalText(newText);
  }, []);

  return {
    localText,
    images,
    setImages,
    handleContentChange,
  };
};
