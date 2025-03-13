import TooltipWrapper from "../TooltipWrapper/TooltipWrapper";
import { getTimeFromNow } from "@/lib/date";

type FeedbackScoreReasonTooltipProps = {
  lastUpdatedAt?: string;
  lastUpdatedBy?: string;
  className?: string;
  reason?: string;
  children: React.ReactNode;
};
const FeedbackScoreReasonTooltip: React.FC<FeedbackScoreReasonTooltipProps> = ({
  reason,
  lastUpdatedAt,
  lastUpdatedBy,
  children,
}) => {
  if (!reason) return <>{children}</>;

  return (
    <TooltipWrapper
      content={
        <div className="flex flex-col gap-1">
          <div className="flex items-center gap-1">
            <div className="comet-body-xs-accented">{lastUpdatedBy}</div>
            {lastUpdatedAt && (
              <div className="comet-body-xs text-slate-400">
                {getTimeFromNow(lastUpdatedAt)}
              </div>
            )}
          </div>
          <div className="whitespace-pre-line break-words">{reason}</div>
        </div>
      }
      delayDuration={100}
    >
      {children}
    </TooltipWrapper>
  );
};

export default FeedbackScoreReasonTooltip;
