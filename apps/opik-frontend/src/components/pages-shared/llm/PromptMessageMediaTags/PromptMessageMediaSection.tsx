import React, { useState } from "react";
import { Image, Paperclip, Video, Music } from "lucide-react";
import { Button } from "@/components/ui/button";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import AddMediaPopover from "./AddMediaPopover";
import MediaTagsList from "./MediaTagsList";
import { cn } from "@/lib/utils";

interface PromptMessageMediaSectionProps {
  images: string[];
  videos: string[];
  audios: string[];
  setImages: (items: string[]) => void;
  setVideos: (items: string[]) => void;
  setAudios: (items: string[]) => void;
  promptVariables?: string[];
  disabled?: boolean;
}

const PromptMessageMediaSection: React.FC<PromptMessageMediaSectionProps> = ({
  images,
  videos,
  audios,
  setImages,
  setVideos,
  setAudios,
  promptVariables,
  disabled = false,
}) => {
  const [openPopover, setOpenPopover] = useState<string | null>(null);

  const handlePopoverChange = (type: string, isOpen: boolean) => {
    setOpenPopover(isOpen ? type : null);
  };

  const isAnyPopoverOpen = openPopover !== null;

  return (
    <div className="mt-3 flex flex-col gap-2">
      <div className="group flex items-center gap-2">
        <div className="flex items-center gap-1">
          <Paperclip className="size-3.5" />
          <span className="comet-body-s">Add file...</span>
        </div>
        <div
          className={cn(
            "flex overflow-hidden transition-all duration-300 ease-out",
            isAnyPopoverOpen
              ? "opacity-100"
              : "opacity-0 group-hover:opacity-100",
          )}
        >
          <AddMediaPopover
            type="image"
            items={images}
            setItems={setImages}
            onOpenChange={(isOpen) => handlePopoverChange("image", isOpen)}
            promptVariables={promptVariables}
          >
            <TooltipWrapper content="Image">
              <Button
                type="button"
                variant="minimal"
                disabled={disabled}
                className={cn(
                  "p-1",
                  openPopover === "image" && "text-foreground",
                )}
              >
                <Image className="size-4" />
              </Button>
            </TooltipWrapper>
          </AddMediaPopover>
          <AddMediaPopover
            type="video"
            items={videos}
            setItems={setVideos}
            onOpenChange={(isOpen) => handlePopoverChange("video", isOpen)}
            promptVariables={promptVariables}
          >
            <TooltipWrapper content="Video">
              <Button
                type="button"
                variant="minimal"
                disabled={disabled}
                className={cn(
                  "p-1",
                  openPopover === "video" && "text-foreground",
                )}
              >
                <Video className="size-4" />
              </Button>
            </TooltipWrapper>
          </AddMediaPopover>
          <AddMediaPopover
            type="audio"
            items={audios}
            setItems={setAudios}
            onOpenChange={(isOpen) => handlePopoverChange("audio", isOpen)}
            promptVariables={promptVariables}
          >
            <TooltipWrapper content="Audio">
              <Button
                type="button"
                variant="minimal"
                className={cn(
                  "p-1",
                  openPopover === "audio" && "text-foreground",
                )}
                disabled={disabled}
              >
                <Music className="size-4" />
              </Button>
            </TooltipWrapper>
          </AddMediaPopover>
        </div>
      </div>

      {(images.length > 0 || videos.length > 0 || audios.length > 0) && (
        <div className="flex min-h-7 flex-wrap items-center gap-1.5">
          <MediaTagsList type="image" items={images} setItems={setImages} />
          <MediaTagsList type="video" items={videos} setItems={setVideos} />
          <MediaTagsList type="audio" items={audios} setItems={setAudios} />
        </div>
      )}
    </div>
  );
};

export default PromptMessageMediaSection;
