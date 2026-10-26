import React, { useRef } from "react";
import { useHotkeys } from "react-hotkeys-hook";

import { Button } from "@/ui/button";
import { HotkeyDisplay } from "@/ui/hotkey-display";
import { Separator } from "@/ui/separator";
import { isKeyForLayerAbovePanel } from "@/shared/ResizableSidePanel/panelHotkeys";

type ArrowNavigationConfig = {
  hasPrevious: boolean;
  hasNext: boolean;
  onChange: (shift: 1 | -1) => void;
};

type ResizableSidePanelArrowNavigationProps = {
  horizontalNavigation?: ArrowNavigationConfig;
  previousHotkey?: string;
  nextHotkey?: string;
  previousLabel?: string;
  nextLabel?: string;
  showSeparator?: boolean;
  ignoreHotkeys?: boolean;
};

const ResizableSidePanelArrowNavigation: React.FunctionComponent<
  ResizableSidePanelArrowNavigationProps
> = ({
  horizontalNavigation,
  previousHotkey,
  nextHotkey,
  previousLabel = "Previous",
  nextLabel = "Next",
  showSeparator = true,
  ignoreHotkeys = false,
}) => {
  const previousButtonRef = useRef<HTMLButtonElement>(null);
  const ignoreKey = (keyboardEvent: KeyboardEvent) =>
    ignoreHotkeys ||
    isKeyForLayerAbovePanel(keyboardEvent, previousButtonRef.current);

  useHotkeys(
    "j",
    () =>
      horizontalNavigation?.hasPrevious && horizontalNavigation.onChange(-1),
    {
      enabled: Boolean(horizontalNavigation),
      enableOnFormTags: false,
      ignoreEventWhen: ignoreKey,
    },
    [horizontalNavigation, ignoreHotkeys],
  );
  useHotkeys(
    "k",
    () => horizontalNavigation?.hasNext && horizontalNavigation.onChange(1),
    {
      enabled: Boolean(horizontalNavigation),
      enableOnFormTags: false,
      ignoreEventWhen: ignoreKey,
    },
    [horizontalNavigation, ignoreHotkeys],
  );

  if (!horizontalNavigation) return null;
  const previous = previousHotkey ?? "J";
  const next = nextHotkey ?? "K";
  return (
    <>
      {showSeparator && (
        <Separator orientation="vertical" className="mx-1 h-4" />
      )}
      <Button
        ref={previousButtonRef}
        variant="outline"
        size="2xs"
        disabled={!horizontalNavigation.hasPrevious}
        onClick={() => horizontalNavigation.onChange(-1)}
        className="gap-1"
      >
        {previousLabel}
        <HotkeyDisplay hotkey={previous} variant="outline" size="2xs" />
      </Button>
      <Button
        variant="outline"
        size="2xs"
        disabled={!horizontalNavigation.hasNext}
        onClick={() => horizontalNavigation.onChange(1)}
        className="gap-1"
      >
        {nextLabel}
        <HotkeyDisplay hotkey={next} variant="outline" size="2xs" />
      </Button>
    </>
  );
};

export default ResizableSidePanelArrowNavigation;
