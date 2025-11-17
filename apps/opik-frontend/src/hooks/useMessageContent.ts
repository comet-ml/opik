import { useCallback, useEffect, useRef, useState } from "react";
import { combineContentWithMedia, parseContentWithMedia } from "@/lib/llm";
import { OnChangeFn } from "@/types/shared";

interface UseMessageContentProps {
  content: string;
  onChangeContent: (newContent: string) => void;
}

interface UseMessageContentReturn {
  localText: string;
  images: string[];
  videos: string[];
  setImages: OnChangeFn<string[]>;
  setVideos: OnChangeFn<string[]>;
  handleContentChange: (newText: string) => void;
}

export const useMessageContent = ({
  content,
  onChangeContent,
}: UseMessageContentProps): UseMessageContentReturn => {
  const lastProcessedContentRef = useRef<string>(content);
  const {
    text: initText,
    images: initImages,
    videos: initVideos,
  } = parseContentWithMedia(content);
  const [localText, setLocalText] = useState(initText);
  const [images, setImages] = useState<string[]>(initImages);
  const [videos, setVideos] = useState<string[]>(initVideos);

  useEffect(() => {
    if (content !== lastProcessedContentRef.current) {
      const {
        text,
        images: parsedImages,
        videos: parsedVideos,
      } = parseContentWithMedia(content);
      setLocalText(text);
      setImages(parsedImages);
      setVideos(parsedVideos);
      lastProcessedContentRef.current = combineContentWithMedia(
        text,
        parsedImages,
        parsedVideos,
      );
    }
  }, [content]);

  useEffect(() => {
    const newContent = combineContentWithMedia(localText, images, videos);
    if (newContent !== lastProcessedContentRef.current) {
      lastProcessedContentRef.current = newContent;
      onChangeContent(newContent);
    }
  }, [images, videos, localText, onChangeContent]);

  const handleContentChange = useCallback(
    (newText: string) => {
      setLocalText(newText);
      const newContent = combineContentWithMedia(newText, images, videos);
      lastProcessedContentRef.current = newContent;
      onChangeContent(newContent);
    },
    [images, videos, onChangeContent],
  );

  return {
    localText,
    images,
    videos,
    setImages,
    setVideos,
    handleContentChange,
  };
};
