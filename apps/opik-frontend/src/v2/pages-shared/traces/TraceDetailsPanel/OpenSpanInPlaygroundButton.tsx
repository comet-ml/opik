import React, { useCallback, useState } from "react";
import { Play } from "lucide-react";

import { Span } from "@/types/traces";
import { Button } from "@/ui/button";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import useLoadSpanIntoPlayground from "@/v2/pages-shared/playground/useLoadSpanIntoPlayground";

type OpenSpanInPlaygroundButtonProps = {
  span: Span;
};

const OpenSpanInPlaygroundButton: React.FC<OpenSpanInPlaygroundButtonProps> = ({
  span,
}) => {
  const [showLoadConfirm, setShowLoadConfirm] = useState(false);
  const { loadSpan, isPlaygroundEmpty, isPending } =
    useLoadSpanIntoPlayground();

  const doLoadIntoPlayground = useCallback(
    () => loadSpan(span),
    [loadSpan, span],
  );

  const handleOpenInPlayground = useCallback(() => {
    if (isPlaygroundEmpty) {
      doLoadIntoPlayground();
    } else {
      setShowLoadConfirm(true);
    }
  }, [isPlaygroundEmpty, doLoadIntoPlayground]);

  return (
    <>
      <TooltipWrapper content="Open in Playground">
        <Button
          variant="ghost"
          size="icon-2xs"
          aria-label="Open in Playground"
          disabled={isPending}
          onClick={handleOpenInPlayground}
        >
          <Play />
        </Button>
      </TooltipWrapper>
      <ConfirmDialog
        open={showLoadConfirm}
        setOpen={setShowLoadConfirm}
        onConfirm={doLoadIntoPlayground}
        title="Load span messages"
        description="Loading this span's messages into the Playground will replace any unsaved changes. This action cannot be undone."
        confirmText="Load messages"
      />
    </>
  );
};

export default OpenSpanInPlaygroundButton;
