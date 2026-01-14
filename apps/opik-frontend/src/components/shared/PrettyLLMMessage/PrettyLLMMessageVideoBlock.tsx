import React, { useMemo } from "react";
import { cn } from "@/lib/utils";
import { ATTACHMENT_TYPE, ParsedMediaData } from "@/types/attachments";
import ImagesListWrapper from "@/components/pages-shared/attachments/ImagesListWrapper/ImagesListWrapper";
import { PrettyLLMMessageVideoBlockProps } from "./types";

const PrettyLLMMessageVideoBlock: React.FC<PrettyLLMMessageVideoBlockProps> = ({
  videos,
  className,
}) => {
  const mediaData: ParsedMediaData[] = useMemo(() => {
    return videos.map((video) => ({
      url: video.url,
      name: video.name,
      type: ATTACHMENT_TYPE.VIDEO,
    }));
  }, [videos]);

  return (
    <div className={cn("w-full py-1", className)}>
      <ImagesListWrapper media={mediaData} />
    </div>
  );
};

export default PrettyLLMMessageVideoBlock;
