import React, { useMemo } from "react";
import { Music, Image, Video, CircleX } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Tag } from "@/components/ui/tag";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import {
  isAudioBase64String,
  isImageBase64String,
  isVideoBase64String,
} from "@/lib/images";

export type MediaType = "image" | "video" | "audio";

export interface MediaTagsListProps {
  type: MediaType;
  items: string[];
  setItems?: (newItems: string[]) => void;
  editable?: boolean;
  preview?: boolean;
}

const isHttpUrl = (value: string): boolean => {
  try {
    const url = new URL(value);
    return url.protocol === "http:" || url.protocol === "https:";
  } catch {
    return false;
  }
};

const MediaTagsList: React.FC<MediaTagsListProps> = ({
  type,
  items,
  setItems,
  editable = true,
  preview = true,
}) => {
  const icon = useMemo(() => {
    if (type === "image") {
      return <Image className="size-3.5 shrink-0" />;
    }
    if (type === "video") {
      return <Video className="size-3.5 shrink-0" />;
    }
    return <Music className="size-3.5 shrink-0" />;
  }, [type]);

  const isPreviewable = (value: string): boolean => {
    if (isHttpUrl(value)) return true;
    if (type === "image") {
      return isImageBase64String(value);
    }
    if (type === "video") {
      return isVideoBase64String(value);
    }
    return isAudioBase64String(value);
  };

  const renderPreview = (value: string) => {
    if (!isPreviewable(value)) {
      return (
        <div className="flex max-w-[240px] flex-col gap-2">
          <p className="comet-body-s text-muted-foreground">
            Preview not available
          </p>
          <p className="comet-body-xs truncate text-muted-foreground">
            {value}
          </p>
        </div>
      );
    }

    if (type === "image") {
      return (
        <div className="flex max-w-[240px] flex-col gap-2">
          <img
            src={value}
            alt="Image preview"
            className="max-h-24 rounded border object-contain"
            onError={(event) => {
              event.currentTarget.style.display = "none";
            }}
          />
        </div>
      );
    }

    if (type === "video") {
      return (
        <div className="flex max-w-[240px] flex-col gap-2">
          <video
            src={value}
            controls
            preload="metadata"
            className="max-h-24 rounded border object-contain"
            onError={(event) => {
              const parent = event.currentTarget.parentElement;
              if (parent) {
                parent.innerHTML = `
                  <p class="comet-body-s text-muted-foreground">Video preview failed</p>
                  <p class="comet-body-xs truncate text-muted-foreground">${value.substring(
                    0,
                    50,
                  )}...</p>
                `;
              }
            }}
          >
            Your browser does not support video playback.
          </video>
        </div>
      );
    }

    return (
      <div className="flex w-[320px] flex-col gap-2">
        <audio
          src={value}
          controls
          preload="metadata"
          className="h-10 w-full"
          onError={(event) => {
            const parent = event.currentTarget.parentElement;
            if (parent) {
              parent.innerHTML = `
                <p class="comet-body-s text-muted-foreground">Audio preview failed</p>
              `;
            }
          }}
        >
          Your browser does not support audio playback.
        </audio>
      </div>
    );
  };

  const handleDeleteItem = (value: string) => {
    setItems?.(items.filter((item) => item !== value));
  };

  if (items.length === 0) {
    return null;
  }

  return (
    <>
      {items.map((value, index) => {
        const tagContent = (
          <Tag
            size="md"
            variant="gray"
            className="group/media-tag max-w-[240px] shrink-0 pr-2 transition-all"
            onClick={(event) => event.stopPropagation()}
          >
            <div className="flex max-w-full items-center">
              {icon}
              <span className="mx-1 min-w-0 truncate">{value}</span>
              {editable && setItems && (
                <Button
                  size="icon-2xs"
                  variant="ghost"
                  className="hidden group-hover/media-tag:flex"
                  onClick={() => handleDeleteItem(value)}
                  aria-label={`Delete ${type}`}
                >
                  <CircleX />
                </Button>
              )}
            </div>
          </Tag>
        );

        if (!preview) {
          return <React.Fragment key={index}>{tagContent}</React.Fragment>;
        }

        return (
          <TooltipWrapper key={index} content={renderPreview(value)}>
            {tagContent}
          </TooltipWrapper>
        );
      })}
    </>
  );
};

export default MediaTagsList;
