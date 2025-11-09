import { Clock } from "lucide-react";
import { formatDate } from "@/lib/date";
import { Tag } from "@/components/ui/tag";
import TooltipWrapper from "../TooltipWrapper/TooltipWrapper";
import capitalize from "lodash/capitalize";
import { RESOURCE_MAP, RESOURCE_TYPE } from "../ResourceLink/ResourceLink";

interface DateTagProps {
  date: string;
  resource: RESOURCE_TYPE;
}

const DateTag = ({ date, resource }: DateTagProps) => {
  const { label } = RESOURCE_MAP[resource];

  if (!date) {
    return null;
  }

  return (
    <TooltipWrapper content={`${capitalize(label)} creation time`}>
      <Tag
        size="md"
        variant="gray"
        className="flex shrink-0 items-center gap-2"
      >
        <Clock className="size-4 shrink-0" />
        <div className="truncate">{formatDate(date)}</div>
      </Tag>
    </TooltipWrapper>
  );
};

DateTag.displayName = "DateTag";

export default DateTag;
