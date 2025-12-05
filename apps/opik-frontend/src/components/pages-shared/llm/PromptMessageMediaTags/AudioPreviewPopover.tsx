import React, { useState } from "react";
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover";

interface AudioPreviewPopoverProps {
  preview: React.ReactNode;
  children: React.ReactNode;
}

// audio preview popover that opens on hover but closes on click
const AudioPreviewPopover: React.FC<AudioPreviewPopoverProps> = ({
  preview,
  children,
}) => {
  const [isOpen, setIsOpen] = useState(false);

  return (
    <Popover open={isOpen}>
      <PopoverTrigger asChild>
        <div
          className="cursor-pointer"
          onMouseEnter={() => setIsOpen(true)}
          onClick={() => setIsOpen(false)}
        >
          {children}
        </div>
      </PopoverTrigger>
      <PopoverContent
        className="w-auto p-3"
        align="start"
        onPointerDownOutside={() => setIsOpen(false)}
        onEscapeKeyDown={() => setIsOpen(false)}
      >
        {preview}
      </PopoverContent>
    </Popover>
  );
};

export default AudioPreviewPopover;

