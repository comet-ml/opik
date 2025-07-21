import { Clock } from "lucide-react";
import { formatDate } from "@/lib/date";
import { Tag } from "@/components/ui/tag";
import React from "react";

interface DateTagProps {
  date: string;
}

const DateTag = ({ date }: DateTagProps) => {
  return (
    <Tag size="md" variant="gray" className="flex shrink-0 items-center gap-2">
      <Clock className="size-4 shrink-0" />
      <div className="truncate">{formatDate(date)}</div>
    </Tag>
  );
};

DateTag.displayName = "DateTag";

export default DateTag;
