import { Separator } from "@/components/ui/separator";
import TooltipWrapper from "../TooltipWrapper/TooltipWrapper";
import { getTimeFromNow } from "@/lib/date";

type FeedbackScoreReasonTooltipProps = {
  className?: string;
  reasons?: {
    author?: string;
    reason: string;
    lastUpdatedAt?: string;
    value?: number;
  }[];
  children: React.ReactNode;
};
const FeedbackScoreReasonTooltip: React.FC<FeedbackScoreReasonTooltipProps> = ({
  reasons,
  children,
}) => {
  if (!reasons) return <>{children}</>;

  return (
    <TooltipWrapper
      content={
        <div className="flex flex-col gap-1">
          {reasons.map(({ author, reason, lastUpdatedAt, value }, index) => (
            <div key={index}>
              <div className="flex flex-col gap-1">
                <div className="flex items-center gap-1">
                  <div className="comet-body-xs-accented">{author}</div>
                  {value && (
                    <div className="comet-body-xs-accented -ml-0.5">
                      ({value})
                    </div>
                  )}
                  {lastUpdatedAt && (
                    <div className="comet-body-xs text-slate-400">
                      {getTimeFromNow(lastUpdatedAt)}
                    </div>
                  )}
                </div>
                <div className="whitespace-pre-line break-words">{reason}</div>
              </div>
              {index < reasons.length - 1 && <Separator className="my-1" />}
            </div>
          ))}
        </div>
      }
      delayDuration={100}
      stopClickPropagation
    >
      {children}
    </TooltipWrapper>
  );
};

export default FeedbackScoreReasonTooltip;
