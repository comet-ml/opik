import React, { useMemo } from "react";
import { cn } from "@/lib/utils";
import { ATTACHMENT_TYPE, ParsedMediaData } from "@/types/attachments";
import ImagesListWrapper from "@/components/shared/attachments/ImagesListWrapper/ImagesListWrapper";
import { PrettyLLMMessageVideoBlockProps } from "./types";
import { useMediaContext } from "@/components/shared/PrettyLLMMessage/llmMessages/MediaContext";

/**
 * Pure presentation component for displaying videos in LLM messages.
 * Resolves placeholders like "[video_0]" using MediaContext.
 * No API calls - all data comes from context.
 */
const PrettyLLMMessageVideoBlock: React.FC<PrettyLLMMessageVideoBlockProps> = ({
  videos,
  className,
}) => {
  const { resolveMedia } = useMediaContext();

  // Resolve placeholders to actual URLs using centralized resolver
  const mediaData: ParsedMediaData[] = useMemo(() => {
    return videos.map((video) => {
      const resolved = resolveMedia(video.url, video.name);
      return {
        url: resolved.url,
        name: resolved.name,
        type: ATTACHMENT_TYPE.VIDEO,
      };
    });
  }, [videos, resolveMedia]);

  return (
    <div className={cn("w-full py-1", className)}>
      <ImagesListWrapper media={mediaData} />
    </div>
  );
};

export default PrettyLLMMessageVideoBlock;
