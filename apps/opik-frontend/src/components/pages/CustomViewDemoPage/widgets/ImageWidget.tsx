import React, { useState } from "react";
import { Expand } from "lucide-react";
import { WidgetProps } from "@/types/custom-view";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import ZoomPanContainer from "@/components/shared/ZoomPanContainer/ZoomPanContainer";

const ImageWidget: React.FC<WidgetProps> = ({ value, label }) => {
  const [isOpen, setIsOpen] = useState(false);

  if (value === null || value === undefined) {
    return (
      <div className="rounded-lg border border-border bg-white p-4 shadow-sm">
        <div className="comet-body-s-accented mb-2">{label}</div>
        <div className="text-sm text-muted-slate">No data</div>
      </div>
    );
  }

  const imageUrl = String(value);

  return (
    <>
      <div className="rounded-lg border border-border bg-white p-4 shadow-sm">
        <div className="comet-body-s-accented mb-3">{label}</div>
        <div className="group relative">
          <img
            src={imageUrl}
            alt={label}
            className="max-h-[300px] w-full cursor-pointer rounded-md object-contain"
            onClick={() => setIsOpen(true)}
            loading="lazy"
          />
          <div className="absolute inset-0 flex items-center justify-center bg-black/50 opacity-0 transition-opacity group-hover:opacity-100">
            <Button
              variant="secondary"
              size="sm"
              className="gap-2"
              onClick={() => setIsOpen(true)}
            >
              <Expand className="size-4" />
              View Full Size
            </Button>
          </div>
        </div>
      </div>

      <Dialog open={isOpen} onOpenChange={setIsOpen}>
        <DialogContent className="w-[90vw]">
          <DialogHeader>
            <DialogTitle>{label}</DialogTitle>
          </DialogHeader>
          <div className="size-full h-[80vh] p-4">
            <ZoomPanContainer expandButton={false} className="pt-8">
              <img
                src={imageUrl}
                loading="lazy"
                alt={label}
                className="m-auto size-full object-contain"
              />
            </ZoomPanContainer>
          </div>
        </DialogContent>
      </Dialog>
    </>
  );
};

export default ImageWidget;
