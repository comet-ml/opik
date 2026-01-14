import React, { useMemo } from "react";
import { cn } from "@/lib/utils";
import { ATTACHMENT_TYPE, ParsedMediaData } from "@/types/attachments";
import ImagesListWrapper from "@/components/pages-shared/attachments/ImagesListWrapper/ImagesListWrapper";
import { PrettyLLMMessageImageBlockProps } from "./types";

const PrettyLLMMessageImageBlock: React.FC<PrettyLLMMessageImageBlockProps> = ({
  images,
  className,
}) => {
  const mediaData: ParsedMediaData[] = useMemo(() => {
    return images.map((image) => ({
      url: image.url,
      name: image.name,
      type: ATTACHMENT_TYPE.IMAGE,
    }));
  }, [images]);

  return (
    <div className={cn("w-full py-1", className)}>
      <ImagesListWrapper media={mediaData} />
    </div>
  );
};

export default PrettyLLMMessageImageBlock;
