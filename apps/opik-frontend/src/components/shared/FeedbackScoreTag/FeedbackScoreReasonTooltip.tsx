import TooltipWrapper from "../TooltipWrapper/TooltipWrapper";
import { getTimeFromNow } from "@/lib/date";

type FeedbackScoreReasonTooltipProps = {
  lastUpdatedAt?: string;
  lastUpdatedBy?: string;
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
          <div>{reason}</div>
        </div>
      }
      delayDuration={100}
      className="comet-body-xs max-w-[400px] border border-slate-200 bg-soft-background p-2 text-foreground-secondary"
      showArrow={false}
    >
      {children}
    </TooltipWrapper>
  );
};

export default FeedbackScoreReasonTooltip;
