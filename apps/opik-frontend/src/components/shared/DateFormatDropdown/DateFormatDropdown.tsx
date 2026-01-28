import React from "react";
import { Calendar, Check } from "lucide-react";
import {
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuPortal,
  DropdownMenuSub,
  DropdownMenuSubContent,
  DropdownMenuSubTrigger,
} from "@/components/ui/dropdown-menu";
import {
  DATE_FORMATS,
  DATE_FORMAT_LABELS,
  DATE_FORMAT_EXAMPLES,
  DateFormatType,
} from "@/hooks/useDateFormat";
import { cn } from "@/lib/utils";

type DateFormatDropdownProps = {
  dateFormat: DateFormatType;
  setDateFormat: (format: DateFormatType) => void;
  className?: string;
};

const DateFormatDropdown: React.FC<DateFormatDropdownProps> = ({
  dateFormat,
  setDateFormat,
  className,
}) => {
  return (
    <DropdownMenuGroup>
      <DropdownMenuSub>
        <DropdownMenuSubTrigger className="flex cursor-pointer items-center">
          <Calendar className="mr-2 size-4" />
          <span>Date format</span>
        </DropdownMenuSubTrigger>
        <DropdownMenuPortal>
          <DropdownMenuSubContent className={cn("w-64", className)}>
            {Object.entries(DATE_FORMATS).map(([key, format]) => (
              <DropdownMenuItem
                key={key}
                className="cursor-pointer"
                onClick={() => setDateFormat(format as DateFormatType)}
              >
                <div className="relative flex w-full flex-col pl-6">
                  {dateFormat === format && (
                    <Check className="absolute left-0 top-1/2 size-4 -translate-y-1/2" />
                  )}
                  <span className="comet-body-s">
                    {DATE_FORMAT_LABELS[format]}
                  </span>
                  <span className="comet-body-xs text-muted-foreground">
                    {DATE_FORMAT_EXAMPLES[format]}
                  </span>
                </div>
              </DropdownMenuItem>
            ))}
          </DropdownMenuSubContent>
        </DropdownMenuPortal>
      </DropdownMenuSub>
    </DropdownMenuGroup>
  );
};

export default DateFormatDropdown;
