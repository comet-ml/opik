import { Clock } from "lucide-react";
import { formatDate } from "@/lib/date";
import TooltipWrapper from "../TooltipWrapper/TooltipWrapper";
import capitalize from "lodash/capitalize";
import { RESOURCE_MAP, RESOURCE_TYPE } from "../ResourceLink/ResourceLink";
import { cn } from "@/lib/utils";

interface DateTagProps {
  date: string;
  resource: RESOURCE_TYPE;
}

const DateTag = ({ date, resource }: DateTagProps) => {
  const { label } = RESOURCE_MAP[resource];
  const blueColor = "var(--color-blue)";

  if (!date) {
    return null;
  }

  return (
    <TooltipWrapper content={`${capitalize(label)} creation time`}>
      <div
        className={cn(
          "flex h-6 shrink-0 items-center gap-1.5 rounded-md border border-border px-2",
        )}
      >
        <div
          className="shrink-0 size-2 rounded-[0.15rem]"
          style={{ backgroundColor: blueColor }}
        />
        <Clock className="size-4 shrink-0" style={{ color: blueColor }} />
        <div className="comet-body-s truncate text-muted-slate">
          {formatDate(date)}
        </div>
      </div>
    </TooltipWrapper>
  );
};

DateTag.displayName = "DateTag";

export default DateTag;
