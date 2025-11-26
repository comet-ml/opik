import { useCallback, useMemo } from "react";
import { MessageSquareText } from "lucide-react";
import { Button } from "@/components/ui/button";
import { ROW_HEIGHT } from "@/types/shared";

interface FeedbackScoreReasonToggleProps {
  showReasons: boolean;
  setShowReasons: (show: boolean) => void;
  height?: ROW_HEIGHT | string | null | undefined;
  setHeight?: (height: ROW_HEIGHT) => void;
  scoresColumnsData: Array<{ id: string }>;
  selectedColumns: string[];
}

const FeedbackScoreReasonToggle = ({
  showReasons,
  setShowReasons,
  height,
  setHeight,
  scoresColumnsData,
  selectedColumns,
}: FeedbackScoreReasonToggleProps) => {
  const hasVisibleScoreColumns = useMemo(
    () => scoresColumnsData.some((col) => selectedColumns.includes(col.id)),
    [scoresColumnsData, selectedColumns],
  );

  const handleToggle = useCallback(() => {
    const newShowReasons = !showReasons;
    setShowReasons(newShowReasons);

    if (setHeight && newShowReasons && height === ROW_HEIGHT.small) {
      setHeight(ROW_HEIGHT.medium);
    }
  }, [showReasons, setShowReasons, height, setHeight]);

  if (!hasVisibleScoreColumns) {
    return null;
  }

  return (
    <Button
      variant="ghost"
      size="xs"
      onClick={handleToggle}
      className="h-auto whitespace-nowrap p-0 text-sm leading-none hover:bg-transparent"
    >
      <MessageSquareText className="mr-1.5 size-3.5" />
      {`${showReasons ? "Collapse" : "Expand"} reasons`}
    </Button>
  );
};

export default FeedbackScoreReasonToggle;
