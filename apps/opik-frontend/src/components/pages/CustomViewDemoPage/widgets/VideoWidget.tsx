import React, { useState } from "react";
import ReactPlayer from "react-player";
import { Expand } from "lucide-react";
import { WidgetProps } from "@/types/custom-view";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";

const VideoWidget: React.FC<WidgetProps> = ({ value, label }) => {
  const [isOpen, setIsOpen] = useState(false);

  if (value === null || value === undefined) {
    return (
      <div className="rounded-lg border border-border bg-white p-4 shadow-sm">
        <div className="comet-body-s-accented mb-2">{label}</div>
        <div className="text-sm text-muted-slate">No data</div>
      </div>
    );
  }

  const videoUrl = String(value);
  const isDataUrl = videoUrl.startsWith("data:");

  const renderVideoPlayer = (isFullscreen: boolean = false) => {
    if (isDataUrl) {
      return (
        <video
          src={videoUrl}
          controls
          className="max-h-full max-w-full"
          preload="auto"
          autoPlay={isFullscreen && isOpen}
        >
          Your browser does not support embedded videos.
        </video>
      );
    }

    return (
      <ReactPlayer
        url={videoUrl}
        width="100%"
        height="100%"
        controls
        light={!isFullscreen}
        playing={isFullscreen && isOpen}
      />
    );
  };

  return (
    <>
      <div className="rounded-lg border border-border bg-white p-4 shadow-sm">
        <div className="comet-body-s-accented mb-3">{label}</div>
        <div className="relative">
          <div className="aspect-video w-full overflow-hidden rounded-md bg-black">
            {renderVideoPlayer(false)}
          </div>
          <Button
            variant="secondary"
            size="sm"
            className="mt-2 gap-2"
            onClick={() => setIsOpen(true)}
          >
            <Expand className="size-4" />
            View Fullscreen
          </Button>
        </div>
      </div>

      <Dialog open={isOpen} onOpenChange={setIsOpen}>
        <DialogContent className="w-[800px]">
          <DialogHeader>
            <DialogTitle>{label}</DialogTitle>
          </DialogHeader>
          <div className="aspect-video w-full overflow-hidden rounded-md bg-black">
            {renderVideoPlayer(true)}
          </div>
        </DialogContent>
      </Dialog>
    </>
  );
};

export default VideoWidget;
