import { useCallback, useEffect, useRef, useState } from "react";
import { combineContentWithImages, parseContentWithImages } from "@/lib/llm";
import { OnChangeFn } from "@/types/shared";

interface UseMessageContentProps {
  content: string;
  onChangeContent: (newContent: string) => void;
}

interface UseMessageContentReturn {
  localText: string;
  images: string[];
  setImages: OnChangeFn<string[]>;
  handleContentChange: (newText: string) => void;
}

/**
 * Hook to manage synchronization between external content (with embedded images)
 * and local text/images state. Prevents infinite loops during updates.
 */
export const useMessageContent = ({
  content,
  onChangeContent,
}: UseMessageContentProps): UseMessageContentReturn => {
  // Track the last content we processed to prevent loops
  const lastProcessedContentRef = useRef<string>("");
  const [localText, setLocalText] = useState("");
  const [images, setImages] = useState<string[]>([]);

  // Parse content when it changes from external source
  useEffect(() => {
    // Only parse if content changed from external source (not from our updates)
    if (content !== lastProcessedContentRef.current) {
      const { text, images: parsedImages } = parseContentWithImages(content);
      setLocalText(text);
      setImages(parsedImages);
      lastProcessedContentRef.current = content;
    }
  }, [content]);

  // Update content when images change (but not text)
  useEffect(() => {
    const newContent = combineContentWithImages(localText, images);
    if (newContent !== lastProcessedContentRef.current) {
      lastProcessedContentRef.current = newContent;
      onChangeContent(newContent);
    }
  }, [images, localText, onChangeContent]);

  // Handle text content changes from CodeMirror
  const handleContentChange = useCallback(
    (newText: string) => {
      setLocalText(newText);
      const newContent = combineContentWithImages(newText, images);
      lastProcessedContentRef.current = newContent;
      onChangeContent(newContent);
    },
    [images, onChangeContent],
  );

  return {
    localText,
    images,
    setImages,
    handleContentChange,
  };
};
