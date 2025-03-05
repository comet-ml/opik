import { cn } from "@/lib/utils";
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
  className,
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
      className={cn(
        "comet-body-xs max-w-[400px] border border-slate-200 bg-soft-background p-2 text-foreground-secondary",
        className,
      )}
      showArrow={false}
    >
      {children}
    </TooltipWrapper>
  );
};

export default FeedbackScoreReasonTooltip;
