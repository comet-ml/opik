import React, { useCallback, useEffect, useRef, useState } from "react";
import { Check, Copy, Link } from "lucide-react";
import copy from "clipboard-copy";

import { Button } from "@/ui/button";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { cn } from "@/lib/utils";
import { CopyEntityLabel } from "./types";

const COPIED_STATE_TIMEOUT = 3000;

type CopyActionButtonProps = {
  icon: React.ReactNode;
  label: string;
  onCopy: () => string;
};

const CopyActionButton: React.FunctionComponent<CopyActionButtonProps> = ({
  icon,
  label,
  onCopy,
}) => {
  const [isCopied, setIsCopied] = useState(false);
  const timerRef = useRef<number | null>(null);

  useEffect(
    () => () => {
      if (timerRef.current) window.clearTimeout(timerRef.current);
    },
    [],
  );

  const handleClick = useCallback(() => {
    copy(onCopy());
    setIsCopied(true);

    if (timerRef.current) window.clearTimeout(timerRef.current);
    timerRef.current = window.setTimeout(
      () => setIsCopied(false),
      COPIED_STATE_TIMEOUT,
    );
  }, [onCopy]);

  const currentLabel = isCopied ? "Copied" : label;

  return (
    <TooltipWrapper content={currentLabel}>
      <Button
        variant="minimal"
        size="icon-2xs"
        aria-label={currentLabel}
        onClick={handleClick}
      >
        {isCopied ? <Check className="text-chart-green" /> : icon}
      </Button>
    </TooltipWrapper>
  );
};

type CopyEntityActionsProps = {
  entityId: string;
  entityLabel: CopyEntityLabel;
  className?: string;
  // A URL is page-scoped, so only the surface that owns the page offers a link
  // action. Nested entities (a span inside a trace) copy their id only.
  withLink?: boolean;
};

const CopyEntityActions: React.FunctionComponent<CopyEntityActionsProps> = ({
  entityId,
  entityLabel,
  className,
  withLink = true,
}) => {
  const copyId = useCallback(() => entityId, [entityId]);
  const copyLink = useCallback(() => window.location.href, []);

  return (
    <div className={cn("flex shrink-0 items-center gap-1", className)}>
      <CopyActionButton
        icon={<Copy />}
        label={`Copy ${entityLabel} ID`}
        onCopy={copyId}
      />
      {withLink && (
        <CopyActionButton
          icon={<Link />}
          label={`Copy ${entityLabel} link`}
          onCopy={copyLink}
        />
      )}
    </div>
  );
};

export default CopyEntityActions;
