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
import { OnChangeFn } from "@/types/shared";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { isImageBase64String, isVideoBase64String } from "@/lib/images";

type MediaType = "image" | "video";

export type PromptMessageMediaTagsProps = {
  items: string[];
  setItems: OnChangeFn<string[]>;
  type: MediaType;
  align?: "start" | "end";
  preview?: boolean;
  editable?: boolean;
  maxItems?: number;
};

const DEFAULT_MAX_ITEMS: Record<MediaType, number> = {
  image: 3,
  video: 1,
};
const MAX_DISPLAY_LENGTH = 40;

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
          className="max-h-24 rounded border object-contain"
          onError={(event) => {
            event.currentTarget.style.display = "none";
          }}
        >
          Your browser does not support embedded videos.
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

    setItems([...items, trimmed]);
    setNewItem("");
    setOpen(false);
  };

  const handleDeleteItem = (value: string) => {
    setItems(items.filter((item) => item !== value));
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
      ? "Enter image URL, base64, or template variable"
      : "Enter video URL, base64, or template variable";
  const helperText =
    type === "image"
      ? "You can add a base64 string, image URL, or template variable "
      : "You can add a base64 string, video URL, or template variable ";

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
        <Popover onOpenChange={setOpen} open={open}>
          <TooltipWrapper
            content={`Add ${type}: ${type} URL, base64 data, or template variable {{${type}}}`}
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
                <Input
                  placeholder={placeholder}
                  value={newItem}
                  onChange={(event) => setNewItem(event.target.value)}
                  onKeyDown={(event) => {
                    if (event.key === "Enter") {
                      handleAddItem();
                    }
                  }}
                />
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
      )}
    </div>
  );
};

export default PromptMessageMediaTags;
