import React, { useMemo } from "react";
import { cn } from "@/lib/utils";
import { ATTACHMENT_TYPE, ParsedMediaData } from "@/types/attachments";
import ImagesListWrapper from "@/components/shared/attachments/ImagesListWrapper/ImagesListWrapper";
import { PrettyLLMMessageImageBlockProps } from "./types";
import { useMediaContext } from "@/components/shared/PrettyLLMMessage/llmMessages/MediaContext";

/**
 * Pure presentation component for displaying images in LLM messages.
 * Resolves placeholders like "[image_0]" using MediaContext.
 * No API calls - all data comes from context.
 */
const PrettyLLMMessageImageBlock: React.FC<PrettyLLMMessageImageBlockProps> = ({
  images,
  className,
}) => {
  const { resolveMedia } = useMediaContext();

  // Resolve placeholders to actual URLs using centralized resolver
  const mediaData: ParsedMediaData[] = useMemo(() => {
    return images.map((image) => {
      const resolved = resolveMedia(image.url, image.name);
      return {
        url: resolved.url,
        name: resolved.name,
        type: ATTACHMENT_TYPE.IMAGE,
      };
    });
  }, [images, resolveMedia]);

  return (
    <div className={cn("w-full py-1", className)}>
      <ImagesListWrapper media={mediaData} />
    </div>
  );
};

export default PrettyLLMMessageImageBlock;
