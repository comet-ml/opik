import React from "react";
import { useHotkeys } from "react-hotkeys-hook";
import { Button } from "@/components/ui/button";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { LastSectionValue } from "./TraceDetailsPanel";
import { Explainer } from "@/types/shared";
import ExplainerIcon from "@/components/shared/ExplainerIcon/ExplainerIcon";

type LastSectionLayoutProps = {
  title: string;
  closeTooltipContent?: string;
  closeText?: string;
  lastSection?: LastSectionValue | null;
  setLastSection: (v: LastSectionValue | null) => void;
  children: React.ReactNode;
  explainer?: Explainer;
};

const HOTKEYS = ["Esc"];

const LastSectionLayout: React.FC<LastSectionLayoutProps> = ({
  title,
  closeTooltipContent = "Close",
  closeText = "Close",
  lastSection,
  setLastSection,
  children,
  explainer,
}) => {
  useHotkeys(
    "Escape",
    (keyboardEvent: KeyboardEvent) => {
      if (!lastSection) return;
      keyboardEvent.stopPropagation();

      if (keyboardEvent.code === "Escape") {
        setLastSection(null);
      }
    },
    [lastSection],
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
            onClick={() => setLastSection(null)}
          >
            {closeText}
          </Button>
        </TooltipWrapper>
      </div>
      {children}
    </div>
  );
};

export default LastSectionLayout;
