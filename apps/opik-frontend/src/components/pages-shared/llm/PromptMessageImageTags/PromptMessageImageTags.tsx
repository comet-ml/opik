import React, { useState } from "react";
import { Image, Plus } from "lucide-react";
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
import { isImageBase64String } from "@/lib/images";

export type PromptMessageImageTagsProps = {
  images: string[];
  setImages: OnChangeFn<string[]>;
  align?: "start" | "end";
  preview?: boolean;
  editable?: boolean;
};

const MAX_IMAGES = 3;
const MAX_DISPLAY_LENGTH = 40;

const PromptMessageImageTags: React.FunctionComponent<
  PromptMessageImageTagsProps
> = ({
  images = [],
  setImages,
  align = "start",
  preview = true,
  editable = true,
}) => {
  const { toast } = useToast();
  const [open, setOpen] = useState(false);
  const [newImage, setNewImage] = useState<string>("");

  const truncateImageString = (imageStr: string) => {
    if (imageStr.length <= MAX_DISPLAY_LENGTH) {
      return imageStr;
    }
    return `${imageStr.substring(0, MAX_DISPLAY_LENGTH)}...`;
  };

  const isImageUrl = (str: string): boolean => {
    try {
      const url = new URL(str);
      return url.protocol === "http:" || url.protocol === "https:";
    } catch {
      return false;
    }
  };

  const isPreviewableImage = (imageStr: string): boolean => {
    return isImageBase64String(imageStr) || isImageUrl(imageStr);
  };

  const renderImagePreview = (imageStr: string) => {
    if (!preview) {
      return imageStr;
    }

    if (isPreviewableImage(imageStr)) {
      return (
        <div className="flex max-w-[200px] flex-col gap-2">
          <img
            src={imageStr}
            alt="Image preview"
            className="max-h-24 rounded border object-contain"
            onError={(e) => {
              e.currentTarget.style.display = "none";
            }}
          />
        </div>
      );
    }

    return (
      <div className="flex max-w-[200px] flex-col gap-2">
        <p className="comet-body-s text-muted-foreground">
          Preview not available
        </p>
        <p className="comet-body-xs truncate text-muted-foreground">
          {imageStr}
        </p>
      </div>
    );
  };

  const handleAddImage = () => {
    if (!newImage.trim()) return;

    if (images.length >= MAX_IMAGES) {
      toast({
        title: "Maximum limit reached",
        description: `You can only add up to ${MAX_IMAGES} images`,
        variant: "destructive",
      });
      return;
    }

    if (images.includes(newImage)) {
      toast({
        title: "Error",
        description: "This image URL already exists",
        variant: "destructive",
      });
      return;
    }

    setImages([...images, newImage]);
    setNewImage("");
    setOpen(false);
  };

  const handleDeleteImage = (imageToDelete: string) => {
    setImages(images.filter((img) => img !== imageToDelete));
  };

  const canAddMore = images.length < MAX_IMAGES;

  return (
    <div
      className="flex min-h-7 w-full flex-wrap items-center gap-1.5 overflow-x-hidden"
      onClick={(e) => e.stopPropagation()}
    >
      {images.map((image, index) => {
        const tagContent = (
          <Tag
            size="md"
            variant="gray"
            className="group/image-tag max-w-full shrink-0 pr-2 transition-all"
          >
            <div className="flex max-w-full items-center">
              <Image className="size-3.5 shrink-0" />
              <span className="mx-1 truncate">
                {truncateImageString(image)}
              </span>
              {editable && (
                <Button
                  size="icon-2xs"
                  variant="ghost"
                  className="hidden group-hover/image-tag:flex"
                  onClick={() => handleDeleteImage(image)}
                  aria-label="Delete image"
                >
                  <CircleX />
                </Button>
              )}
            </div>
          </Tag>
        );

        return preview ? (
          <TooltipWrapper key={index} content={renderImagePreview(image)}>
            {tagContent}
          </TooltipWrapper>
        ) : (
          <React.Fragment key={index}>{tagContent}</React.Fragment>
        );
      })}
      {editable && canAddMore && (
        <Popover onOpenChange={setOpen} open={open}>
          <TooltipWrapper content="Add image: base64 string, image URL, or template variable {{image}}">
            <PopoverTrigger asChild>
              <Button
                data-testid="add-image-button"
                variant="outline"
                size="icon-2xs"
                aria-label="Add image"
              >
                <Plus />
              </Button>
            </PopoverTrigger>
          </TooltipWrapper>
          <PopoverContent className="w-[460px] p-6" align={align}>
            <div className="space-y-3">
              <div className="flex gap-2">
                <Input
                  placeholder="Enter image URL, base64, or template variable"
                  value={newImage}
                  onChange={(event) => setNewImage(event.target.value)}
                  onKeyDown={(event) => {
                    if (event.key === "Enter") {
                      handleAddImage();
                    }
                  }}
                />
                <Button variant="default" onClick={handleAddImage}>
                  Add
                </Button>
              </div>
              <p className="comet-body-xs text-muted-foreground">
                You can add a base64 string, image URL, or template variable{" "}
                <code>{`{{image}}`}</code>
              </p>
            </div>
          </PopoverContent>
        </Popover>
      )}
    </div>
  );
};

export default PromptMessageImageTags;
