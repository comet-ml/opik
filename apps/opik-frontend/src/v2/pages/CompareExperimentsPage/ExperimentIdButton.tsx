import React, { useCallback, useEffect, useRef, useState } from "react";
import { Check, Copy } from "lucide-react";
import copy from "clipboard-copy";

import { Button } from "@/ui/button";
import {
  Tooltip,
  TooltipContent,
  TooltipPortal,
  TooltipTrigger,
} from "@/ui/tooltip";
import { TOOLTIP_DELAY_DURATION } from "@/constants/shared";

const COPIED_STATE_TIMEOUT = 3000;
const TRUNCATE_EDGE_LENGTH = 3;
// Lucide icons use a 24x24 viewBox, so at size-3 (12px) strokes scale by 0.5:
// strokeWidth 2 paints a true 1px line, 1.5 paints 0.75px.
//
// The copy glyph packs far more ink into 12px than the neighbouring arrow-up-right
// tag icon, so at a matching stroke it reads heavier. 1.5 lightens it back to a
// comparable weight.
const COPY_ICON_STROKE_WIDTH = 1.5;
// The check glyph is only two strokes, so it keeps the full 1px to stay legible.
const CHECK_ICON_STROKE_WIDTH = 2;

const truncateId = (id: string) =>
  id.length > TRUNCATE_EDGE_LENGTH * 2
    ? `${id.slice(0, TRUNCATE_EDGE_LENGTH)}…${id.slice(-TRUNCATE_EDGE_LENGTH)}`
    : id;

type ExperimentIdButtonProps = {
  experimentId: string;
};

const ExperimentIdButton: React.FunctionComponent<ExperimentIdButtonProps> = ({
  experimentId,
}) => {
  const [isCopied, setIsCopied] = useState(false);
  const [isHovered, setIsHovered] = useState(false);
  const timerRef = useRef<number | null>(null);

  // Clear on unmount so the timer can't set state after teardown. Repeated
  // clicks restart the countdown rather than stacking timers.
  useEffect(
    () => () => {
      if (timerRef.current) window.clearTimeout(timerRef.current);
    },
    [],
  );

  const copyClickHandler = useCallback(() => {
    copy(experimentId);
    setIsCopied(true);

    if (timerRef.current) window.clearTimeout(timerRef.current);
    timerRef.current = window.setTimeout(
      () => setIsCopied(false),
      COPIED_STATE_TIMEOUT,
    );
  }, [experimentId]);

  // "ID copied" is tied to the check icon's lifetime: forced open while copied
  // regardless of pointer position, and gone the moment the icon reverts. The
  // full-ID tooltip keeps plain hover behavior.
  return (
    <Tooltip
      open={isCopied || isHovered}
      onOpenChange={setIsHovered}
      delayDuration={isCopied ? 0 : TOOLTIP_DELAY_DURATION}
    >
      {/* size 2xs (h-6 px-2) + rounded-sm match the sibling Dataset/Test suite
          tag, so the header row stays visually consistent. */}
      <TooltipTrigger asChild>
        <Button
          size="2xs"
          variant="outline"
          className="shrink-0 rounded-sm bg-transparent"
          onClick={copyClickHandler}
        >
          <span className="comet-body-s text-foreground">ID:</span>
          <span className="comet-body-s-accented ml-1">
            {truncateId(experimentId)}
          </span>
          {isCopied ? (
            <Check
              className="ml-1 size-3 shrink-0 text-chart-green opacity-100"
              strokeWidth={CHECK_ICON_STROKE_WIDTH}
            />
          ) : (
            <Copy
              className="ml-1 size-3 shrink-0 text-foreground"
              strokeWidth={COPY_ICON_STROKE_WIDTH}
            />
          )}
        </Button>
      </TooltipTrigger>
      <TooltipPortal>
        <TooltipContent collisionPadding={16}>
          {isCopied ? "ID copied" : `Experiment ID: ${experimentId}`}
        </TooltipContent>
      </TooltipPortal>
    </Tooltip>
  );
};

export default ExperimentIdButton;
