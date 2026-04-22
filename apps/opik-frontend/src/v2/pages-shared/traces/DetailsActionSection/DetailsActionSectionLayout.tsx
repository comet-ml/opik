import React from "react";
import { useHotkeys } from "react-hotkeys-hook";
import { X } from "lucide-react";
import { Button } from "@/ui/button";

import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import ExplainerIcon from "@/shared/ExplainerIcon/ExplainerIcon";
import { DetailsActionSectionValue } from "./types";
import { Explainer } from "@/types/shared";

type DetailsActionSectionLayoutProps = {
  title: string;
  closeTooltipContent?: string;
  closeText?: string;
  activeSection?: DetailsActionSectionValue | null;
  setActiveSection: (v: DetailsActionSectionValue | null) => void;
  children: React.ReactNode;
  explainer?: Explainer;
  tag?: React.ReactNode;
  button?: React.ReactNode;
};

const HOTKEYS = ["Esc"];

const DetailsActionSectionLayout: React.FC<DetailsActionSectionLayoutProps> = ({
  title,
  closeTooltipContent = "Close",
  activeSection,
  setActiveSection,
  children,
  explainer,
  tag,
  button,
}) => {
  useHotkeys(
    "Escape",
    (keyboardEvent: KeyboardEvent) => {
      if (!activeSection) return;
      keyboardEvent.stopPropagation();

      if (keyboardEvent.code === "Escape") {
        setActiveSection(null);
      }
    },
    [activeSection],
  );

  return (
    <div className="flex size-full min-w-60 flex-col overflow-hidden">
      <div className="flex h-10 shrink-0 items-center justify-between gap-2 overflow-x-hidden border-b bg-muted/50 px-4">
        <div className="flex items-center gap-2 overflow-x-hidden">
          <div className="comet-body-xs-accented flex w-full items-center gap-1 text-foreground">
            <span className="truncate">{title}</span>
            {explainer && <ExplainerIcon {...explainer} />}
            {tag}
          </div>
        </div>
        <div className="flex items-center gap-1">
          {button}
          <TooltipWrapper content={closeTooltipContent} hotkeys={HOTKEYS}>
            <Button
              variant="ghost"
              size="icon-2xs"
              onClick={() => setActiveSection(null)}
            >
              <X />
            </Button>
          </TooltipWrapper>
        </div>
      </div>
      {children}
    </div>
  );
};

export default DetailsActionSectionLayout;
