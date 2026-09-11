import React from "react";
import { ChevronsRight, X } from "lucide-react";

import { Button } from "@/ui/button";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";

type ResizableSidePanelTopBarVariant = "form" | "info";

type ResizableSidePanelTopBarProps = {
  variant?: ResizableSidePanelTopBarVariant;
  title?: React.ReactNode;
  leftIcon?: React.ReactNode;
  children?: React.ReactNode;
  onClose: () => void;
};

const ResizableSidePanelTopBar: React.FunctionComponent<
  ResizableSidePanelTopBarProps
> = ({ variant = "info", title, leftIcon, children, onClose }) => {
  const CloseIcon = variant === "form" ? X : ChevronsRight;
  return (
    <div className="flex flex-auto items-center justify-between">
      <div className="flex items-center gap-1 overflow-hidden">
        <TooltipWrapper content="Close panel">
          <Button variant="ghost" size="icon-2xs" onClick={onClose}>
            <CloseIcon />
            <span className="sr-only">Close</span>
          </Button>
        </TooltipWrapper>
        {leftIcon}
        {title !== undefined && (
          <span className="comet-body-s-accented truncate">{title}</span>
        )}
      </div>
      {children !== undefined && (
        <div className="flex shrink-0 items-center gap-2 pl-4">{children}</div>
      )}
    </div>
  );
};

export default ResizableSidePanelTopBar;
