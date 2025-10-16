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

export const useMessageContent = ({
  content,
  onChangeContent,
}: UseMessageContentProps): UseMessageContentReturn => {
  const lastProcessedContentRef = useRef<string>(content);
  const { text: initText, images: initImages } =
    parseContentWithImages(content);
  const [localText, setLocalText] = useState(initText);
  const [images, setImages] = useState<string[]>(initImages);

  useEffect(() => {
    if (content !== lastProcessedContentRef.current) {
      const { text, images: parsedImages } = parseContentWithImages(content);
      setLocalText(text);
      setImages(parsedImages);
      lastProcessedContentRef.current = combineContentWithImages(
        text,
        parsedImages,
      );
    }
  }, [content]);

  useEffect(() => {
    const newContent = combineContentWithImages(localText, images);
    if (newContent !== lastProcessedContentRef.current) {
      lastProcessedContentRef.current = newContent;
      onChangeContent(newContent);
    }
  }, [images, localText, onChangeContent]);

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
