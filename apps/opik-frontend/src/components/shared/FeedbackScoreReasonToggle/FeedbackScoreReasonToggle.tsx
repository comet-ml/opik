import React, { useCallback } from "react";
import { MessageSquareText } from "lucide-react";
import { Button } from "@/components/ui/button";
import { ROW_HEIGHT } from "@/types/shared";

type FeedbackScoreReasonToggleProps = {
  showReasons: boolean;
  setShowReasons: (show: boolean) => void;
  height?: ROW_HEIGHT | string | null | undefined;
  setHeight?: (height: ROW_HEIGHT) => void;
};

const FeedbackScoreReasonToggle: React.FC<FeedbackScoreReasonToggleProps> = ({
  showReasons,
  setShowReasons,
  height,
  setHeight,
}) => {
  const handleToggle = useCallback(() => {
    const newShowReasons = !showReasons;
    setShowReasons(newShowReasons);

    // If expanding reasons and row height is small, change to medium
    if (setHeight && newShowReasons && height === ROW_HEIGHT.small) {
      setHeight(ROW_HEIGHT.medium);
    }
  }, [showReasons, setShowReasons, height, setHeight]);

  return (
    <Button
      variant="ghost"
      size="xs"
      onClick={handleToggle}
      className="h-auto p-0 text-sm leading-none hover:bg-transparent"
    >
      <MessageSquareText className="mr-1.5 size-3.5" />
      {showReasons ? "Collapse reasons" : "Expand reasons"}
    </Button>
  );
};

export default FeedbackScoreReasonToggle;
