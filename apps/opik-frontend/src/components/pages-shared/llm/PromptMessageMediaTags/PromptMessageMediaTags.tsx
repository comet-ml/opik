import React, { useMemo, useState } from "react";
import { Image, Plus, Video } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover";
import { Input } from "@/components/ui/input";
import { useToast } from "@/components/ui/use-toast";
import { Tag } from "@/components/ui/tag";
import { CircleX } from "lucide-react";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { isImageBase64String, isVideoBase64String } from "@/lib/images";
/* TODO: Temporarily disabled - restore when base64 support is re-enabled */
// import { useMediaFileUpload } from "@/hooks/useMediaFileUpload";
// import { useBase64InputHandler } from "@/hooks/useBase64InputHandler";

type MediaType = "image" | "video";

export type PromptMessageMediaTagsProps = {
  items: string[];
  setItems?: (newItems: string[]) => void;
  type: MediaType;
  align?: "start" | "end";
  preview?: boolean;
  editable?: boolean;
  maxItems?: number;
};

const DEFAULT_MAX_ITEMS: Record<MediaType, number> = {
  image: Infinity,
  video: Infinity,
};
const MAX_DISPLAY_LENGTH = 40;

/* TODO: Temporarily disabled - restore when base64 support is re-enabled */
/*
const ACCEPTED_FILE_TYPES: Record<MediaType, string> = {
  image: SUPPORTED_IMAGE_FORMATS,
  video: SUPPORTED_VIDEO_FORMATS,
};

const MAX_FILE_SIZE_MB: Record<MediaType, number> = {
  image: 200,
  video: 2000,
};
*/

const PromptMessageMediaTags: React.FunctionComponent<
  PromptMessageMediaTagsProps
> = ({
  items = [],
  setItems,
  type,
  align = "start",
  preview = true,
  editable = true,
  maxItems,
}) => {
  const { toast } = useToast();
  const [open, setOpen] = useState(false);
  const [newItem, setNewItem] = useState<string>("");

  const resolvedMaxItems = maxItems ?? DEFAULT_MAX_ITEMS[type];

  /* TODO: Temporarily disabled - restore when base64 support is re-enabled */
  /*
  const { fileInputRef, handleFileSelect } = useMediaFileUpload({
    currentItemsCount: items.length,
    maxItems: resolvedMaxItems,
    maxSizeMB: MAX_FILE_SIZE_MB[type],
    onFilesConverted: (base64Items) => {
      setItems([...items, ...base64Items]);
    },
  });

  const { isProcessing, handleBase64Input } = useBase64InputHandler({
    currentItemsCount: items.length,
    maxItems: resolvedMaxItems,
    maxSizeMB: MAX_FILE_SIZE_MB[type],
    type,
    existingItems: items,
    onBase64Converted: (base64) => {
      setItems([...items, base64]);
    },
  });
  */

  const truncateMediaString = (value: string) => {
    if (value.length <= MAX_DISPLAY_LENGTH) {
      return value;
    }
    return `${value.substring(0, MAX_DISPLAY_LENGTH)}...`;
  };

  const isHttpUrl = (value: string): boolean => {
    try {
      const url = new URL(value);
      return url.protocol === "http:" || url.protocol === "https:";
    } catch {
      return false;
    }
  };

  const validateMediaUrl = (
    url: string,
  ): { valid: boolean; error?: string } => {
    // Check if it's a valid HTTP/HTTPS URL
    if (!isHttpUrl(url)) {
      // Allow template variables like {{image}} or {{video}}
      if (url.match(/^\{\{.+\}\}$/)) {
        return { valid: true };
      }
      return { valid: false, error: "Please enter a valid HTTP or HTTPS URL" };
    }

    return { valid: true };
  };

  const isPreviewable = (value: string): boolean => {
    if (isHttpUrl(value)) return true;
    if (type === "image") {
      return isImageBase64String(value);
    }
    return isVideoBase64String(value);
  };

  const renderPreview = (value: string) => {
    if (!preview) {
      return value;
    }

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
  };

  const handleAddItem = () => {
    const trimmed = newItem.trim();
    if (!trimmed) return;

    if (items.length >= resolvedMaxItems) {
      toast({
        title: "Maximum limit reached",
        description: `You can only add up to ${resolvedMaxItems} ${
          type === "image" ? "images" : "videos"
        }`,
        variant: "destructive",
      });
      return;
    }

    if (items.includes(trimmed)) {
      toast({
        title: "Error",
        description: `This ${type} already exists`,
        variant: "destructive",
      });
      return;
    }

    // Validate URL
    const validation = validateMediaUrl(trimmed);
    if (!validation.valid) {
      toast({
        title: "Invalid URL",
        description: validation.error,
        variant: "destructive",
      });
      return;
    }

    setItems?.([...items, trimmed]);
    setNewItem("");
    setOpen(false);
  };

  const handleDeleteItem = (value: string) => {
    setItems?.(items.filter((item) => item !== value));
  };

  const handleInputChange = (event: React.ChangeEvent<HTMLInputElement>) => {
    const value = event.target.value;
    setNewItem(value);
  };

  const canAddMore = items.length < resolvedMaxItems;

  const icon = useMemo(
    () =>
      type === "image" ? (
        <Image className="size-3.5 shrink-0" />
      ) : (
        <Video className="size-3.5 shrink-0" />
      ),
    [type],
  );
  const placeholder =
    type === "image"
      ? "Enter image URL or template variable"
      : "Enter video URL or template variable";
  const helperText =
    type === "image"
      ? "You can add an image URL or template variable. "
      : "You can add a video URL or template variable. ";

  return (
    <div
      className="flex min-h-7 w-full flex-wrap items-center gap-1.5 overflow-x-hidden"
      onClick={(event) => event.stopPropagation()}
    >
      {items.map((value, index) => {
        const tagContent = (
          <Tag
            size="md"
            variant="gray"
            className="group/media-tag max-w-full shrink-0 pr-2 transition-all"
          >
            <div className="flex max-w-full items-center">
              {icon}
              <span className="mx-1 truncate">
                {truncateMediaString(value)}
              </span>
              {editable && (
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

        return preview ? (
          <TooltipWrapper key={index} content={renderPreview(value)}>
            {tagContent}
          </TooltipWrapper>
        ) : (
          <React.Fragment key={index}>{tagContent}</React.Fragment>
        );
      })}
      {editable && canAddMore && (
        <>
          {/* TODO: Temporarily disabled - restore when base64 support is re-enabled */}
          {/*
          <input
            ref={fileInputRef}
            type="file"
            accept={ACCEPTED_FILE_TYPES[type]}
            multiple={type === "image"}
            className="hidden"
            onChange={handleFileSelect}
          />
          */}
          <Popover onOpenChange={setOpen} open={open}>
            <TooltipWrapper
              content={`Add ${type}: ${type} URL or template variable {{${type}}}`}
            >
              <PopoverTrigger asChild>
                <Button
                  data-testid={`add-${type}-button`}
                  variant="outline"
                  size="icon-2xs"
                  aria-label={`Add ${type}`}
                >
                  <Plus />
                </Button>
              </PopoverTrigger>
            </TooltipWrapper>
            <PopoverContent className="w-[460px] p-6" align={align}>
              <div className="space-y-3">
                <div className="flex gap-2">
                  <div className="relative flex-1">
                    <Input
                      placeholder={placeholder}
                      value={newItem}
                      onChange={handleInputChange}
                      onKeyDown={(event) => {
                        if (event.key === "Enter") {
                          handleAddItem();
                        }
                      }}
                    />
                  </div>
                  {/* TODO: Temporarily disabled - restore when base64 support is re-enabled */}
                  {/*
                  <TooltipWrapper
                    content={`Upload ${type} file${
                      type === "image" ? "s" : ""
                    }`}
                  >
                    <Button
                      data-testid={`upload-${type}-button`}
                      variant="outline"
                      size="default"
                      aria-label={`Upload ${type}`}
                      onClick={() => fileInputRef.current?.click()}
                      disabled={isProcessing}
                    >
                      <Upload className="size-3.5 shrink-0" />
                    </Button>
                  </TooltipWrapper>
                  */}
                  <Button variant="default" onClick={handleAddItem}>
                    Add
                  </Button>
                </div>
                <p className="comet-body-xs text-muted-foreground">
                  {helperText}
                  <code>{`{{${type}}}`}</code>
                </p>
              </div>
            </PopoverContent>
          </Popover>
        </>
      )}
    </div>
  );
};

export default PromptMessageMediaTags;
