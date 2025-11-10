import React from "react";
import { useHotkeys } from "react-hotkeys-hook";
import { X } from "lucide-react";
import { Button } from "@/components/ui/button";

import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import ExplainerIcon from "@/components/shared/ExplainerIcon/ExplainerIcon";
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
    <div className="relative flex size-full min-w-60 flex-col overflow-hidden pt-14">
      <div className="absolute inset-x-0 top-0 flex shrink-0 items-center justify-between gap-2 overflow-x-hidden px-6 pt-6">
        <div className="flex items-center gap-2 overflow-x-hidden">
          <div className="comet-title-s flex w-full items-center gap-1">
            <span className="truncate">{title}</span>
            {explainer && <ExplainerIcon {...explainer} />}
            {tag}
          </div>
        </div>
        <div className="flex items-center gap-1">
          {button}
          <TooltipWrapper content={closeTooltipContent} hotkeys={HOTKEYS}>
            <Button
              variant="outline"
              size="icon-sm"
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
