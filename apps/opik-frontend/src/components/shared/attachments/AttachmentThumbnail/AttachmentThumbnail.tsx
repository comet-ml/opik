import React, { useCallback, useMemo } from "react";
import { DownloadIcon, Expand, ExternalLink } from "lucide-react";

import { cn, isSameDomainUrl } from "@/lib/utils";
import { isOpikS3AttachmentUrl } from "@/lib/attachments";
import { ATTACHMENT_TYPE, AttachmentPreviewData } from "@/types/attachments";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { Button } from "@/components/ui/button";
import { ATTACHMENT_ICON_MAP } from "@/constants/attachments";
import VideoThumbnail from "../VideoThumbnail/VideoThumbnail";

export type AttachmentThumbnailProps = {
  previewData: AttachmentPreviewData;
  onExpand: (data: AttachmentPreviewData) => void;
};

const AttachmentThumbnail: React.FC<AttachmentThumbnailProps> = ({
  previewData,
  onExpand,
}) => {
  const { type, name, url } = previewData;
  const Icon = ATTACHMENT_ICON_MAP[type];

  const allowedDomain = useMemo(() => {
    return isSameDomainUrl(url) || isOpikS3AttachmentUrl(url);
  }, [url]);
  const showDownload = url.startsWith("data:") || allowedDomain;

  const isExpandable =
    type === ATTACHMENT_TYPE.IMAGE ||
    type === ATTACHMENT_TYPE.VIDEO ||
    type === ATTACHMENT_TYPE.AUDIO ||
    (type === ATTACHMENT_TYPE.TEXT && allowedDomain) ||
    (type === ATTACHMENT_TYPE.PDF && allowedDomain) ||
    (type === ATTACHMENT_TYPE.CSV && allowedDomain);

  const expandClickHandler = useCallback(
    (event: React.MouseEvent<unknown>) => {
      event.stopPropagation();
      isExpandable && onExpand(previewData);
    },
    [onExpand, previewData, isExpandable],
  );

  return (
    <div
      key={name}
      className={cn(
        "group relative h-[200px] min-w-[200px] max-w-[300px] rounded-md border p-3 pt-8 bg-primary-foreground",
        isExpandable && "cursor-pointer",
      )}
      onClick={expandClickHandler}
    >
      <div className="absolute inset-x-0 top-0 flex h-8 items-center justify-between gap-2 truncate px-3 py-2">
        <TooltipWrapper content={name}>
          <span className="comet-body-xs truncate text-[#45575F]">{name}</span>
        </TooltipWrapper>
        <div className="-mr-1 hidden shrink-0 items-center gap-1 group-hover:flex">
          {isExpandable && (
            <TooltipWrapper content="Open in fullscreen">
              <Button
                variant="ghost"
                size="icon-2xs"
                onClick={expandClickHandler}
                aria-label="Open in fullscreen"
                className="w-4"
              >
                <Expand className="text-light-slate" />
              </Button>
            </TooltipWrapper>
          )}
          <TooltipWrapper
            content={showDownload ? "Download" : "Open in new tab"}
          >
            <Button variant="ghost" size="icon-2xs" className="w-4" asChild>
              <a
                href={url}
                download={name}
                target="_blank"
                rel="noopener noreferrer"
                onClick={(e) => e.stopPropagation()}
              >
                {showDownload ? (
                  <DownloadIcon className="text-light-slate" />
                ) : (
                  <ExternalLink className="text-light-slate" />
                )}
              </a>
            </Button>
          </TooltipWrapper>
        </div>
      </div>
      {type === ATTACHMENT_TYPE.IMAGE ? (
        <img
          src={url}
          loading="lazy"
          alt={name}
          className="size-full rounded-sm object-contain"
        />
      ) : type === ATTACHMENT_TYPE.VIDEO ? (
        <VideoThumbnail videoUrl={url} name={name} />
      ) : (
        <div className="flex size-full items-center justify-center rounded-sm bg-primary-foreground">
          <Icon className="size-8 text-slate-300" strokeWidth={1.33} />
        </div>
      )}
    </div>
  );
};

export default AttachmentThumbnail;
