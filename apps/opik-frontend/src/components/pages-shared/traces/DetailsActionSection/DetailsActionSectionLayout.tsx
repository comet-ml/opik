import React from "react";
import { useHotkeys } from "react-hotkeys-hook";
import { Button } from "@/components/ui/button";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { DetailsActionSectionValue } from "./types";
import { Explainer } from "@/types/shared";
import ExplainerIcon from "@/components/shared/ExplainerIcon/ExplainerIcon";

type DetailsActionSectionLayoutProps = {
  title: string;
  closeTooltipContent?: string;
  closeText?: string;
  activeSection?: DetailsActionSectionValue | null;
  setActiveSection: (v: DetailsActionSectionValue | null) => void;
  children: React.ReactNode;
  explainer?: Explainer;
};

const HOTKEYS = ["Esc"];

const DetailsActionSectionLayout: React.FC<DetailsActionSectionLayoutProps> = ({
  title,
  closeTooltipContent = "Close",
  closeText = "Close",
  activeSection,
  setActiveSection,
  children,
  explainer,
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
    <div className="flex size-full min-w-60 flex-col overflow-x-hidden pt-6">
      <div className="flex shrink-0 items-center justify-between gap-2 overflow-x-hidden px-6">
        <div className="flex items-center gap-2 overflow-x-hidden">
          <div className="comet-title-s flex w-full items-center gap-1">
            <span className="truncate">{title}</span>
            {explainer && <ExplainerIcon {...explainer} />}
          </div>
        </div>
        <TooltipWrapper content={closeTooltipContent} hotkeys={HOTKEYS}>
          <Button
            variant="ghost"
            size="sm"
            onClick={() => setActiveSection(null)}
          >
            {closeText}
          </Button>
        </TooltipWrapper>
      </div>
      {children}
    </div>
  );
};

export default DetailsActionSectionLayout;
