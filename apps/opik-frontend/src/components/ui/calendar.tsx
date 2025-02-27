import * as React from "react";
import { ChevronLeft, ChevronRight, ChevronDown } from "lucide-react";
import { DayPicker } from "react-day-picker";

import { cn } from "@/lib/utils";
import { buttonVariants } from "@/components/ui/button";

export type CalendarProps = React.ComponentProps<typeof DayPicker> & {
  selectedTime?: string;
  onTimeChange?: (time: string) => void;
};

function Calendar({
  className,
  classNames,
  showOutsideDays = true,
  selectedTime = "00:00",
  onTimeChange,
  ...props
}: CalendarProps) {
  // Convert 24hr time to 12hr format with period
  const convert24to12 = (hour24: number): [number, string] => {
    const period = hour24 >= 12 ? "PM" : "AM";
    const hour12 = hour24 === 0 ? 12 : hour24 > 12 ? hour24 - 12 : hour24;
    return [hour12, period];
  };

  const convert12to24 = (hour12: number, period: string): number => {
    if (period === "PM" && hour12 < 12) return hour12 + 12;
    if (period === "AM" && hour12 === 12) return 0;
    return hour12;
  };

  // Parse initial time
  const [hours24, minutes] = selectedTime?.split(":").map(Number) || [0, 0];
  const [hour12, initialPeriod] = convert24to12(hours24);
  const [period, setPeriod] = React.useState(initialPeriod);

  const handleTimeChange = (
    type: "hours" | "minutes" | "period",
    value: string,
  ) => {
    if (!onTimeChange) return;

    let newHours = hours24;
    let newMinutes = minutes;

    if (type === "hours") {
      const hour12 = parseInt(value);
      newHours = convert12to24(hour12, period);
    } else if (type === "minutes") {
      newMinutes = parseInt(value);
    } else if (type === "period") {
      setPeriod(value);
      newHours = convert12to24(hour12, value);
    }

    const newTime = `${newHours.toString().padStart(2, "0")}:${newMinutes
      .toString()
      .padStart(2, "0")}`;
    onTimeChange(newTime);
  };

  return (
    <div className="space-y-2">
      <DayPicker
        showOutsideDays={showOutsideDays}
        className={cn("p-3", className)}
        classNames={{
          months:
            "flex flex-col sm:flex-row space-y-4 sm:space-x-4 sm:space-y-0",
          month: "space-y-4",
          caption: "flex justify-center pt-1 relative items-center",
          caption_label: "text-sm font-medium",
          nav: "space-x-1 flex items-center",
          nav_button: cn(
            buttonVariants({ variant: "outline" }),
            "h-7 w-7 bg-transparent p-0 opacity-50 hover:opacity-100",
          ),
          nav_button_previous: "absolute left-1",
          nav_button_next: "absolute right-1",
          table: "w-full border-collapse space-y-1",
          head_row: "flex",
          head_cell:
            "text-muted-foreground rounded-md w-9 font-normal text-[0.8rem]",
          row: "flex w-full mt-2",
          cell: "h-9 w-9 text-center text-sm p-0 relative [&:has([aria-selected].day-range-end)]:rounded-r-md [&:has([aria-selected].day-outside)]:bg-accent/50 [&:has([aria-selected])]:bg-accent first:[&:has([aria-selected])]:rounded-l-md last:[&:has([aria-selected])]:rounded-r-md focus-within:relative focus-within:z-20",
          day: cn(
            buttonVariants({ variant: "ghost" }),
            "h-9 w-9 p-0 font-normal aria-selected:opacity-100",
          ),
          day_range_end: "day-range-end",
          day_selected:
            "bg-primary text-primary-foreground hover:bg-primary hover:text-primary-foreground focus:bg-primary focus:text-primary-foreground",
          day_today: "bg-accent text-accent-foreground",
          day_outside:
            "day-outside text-muted-foreground opacity-50 aria-selected:bg-accent/50 aria-selected:text-muted-foreground aria-selected:opacity-30",
          day_disabled: "text-muted-foreground opacity-50",
          day_range_middle:
            "aria-selected:bg-accent aria-selected:text-accent-foreground",
          day_hidden: "invisible",
          ...classNames,
        }}
        components={{
          IconLeft: () => <ChevronLeft className="size-4" />,
          IconRight: () => <ChevronRight className="size-4" />,
        }}
        {...props}
      />
      {onTimeChange && (
        <div className="border-t p-3">
          <div className="flex items-center justify-center gap-2">
            <div className="relative">
              <div className="relative">
                <select
                  value={hour12.toString().padStart(2, "0")}
                  onChange={(e) => handleTimeChange("hours", e.target.value)}
                  className="h-9 cursor-pointer appearance-none rounded-[6px] border border-input bg-background py-1 pl-3 pr-8 text-sm ring-offset-background focus:rounded-[6px] focus:outline-none focus:ring-1 focus:ring-ring"
                >
                  {Array.from({ length: 12 }, (_, i) => {
                    const hour = i + 1;
                    return (
                      <option
                        key={hour}
                        value={hour.toString().padStart(2, "0")}
                      >
                        {hour.toString().padStart(2, "0")}
                      </option>
                    );
                  })}
                </select>
                <ChevronDown className="pointer-events-none absolute right-2 top-1/2 size-4 -translate-y-1/2 opacity-50" />
              </div>
            </div>
            <span className="text-sm font-medium">:</span>
            <div className="relative">
              <div className="relative">
                <select
                  value={minutes.toString().padStart(2, "0")}
                  onChange={(e) => handleTimeChange("minutes", e.target.value)}
                  className="h-9 cursor-pointer appearance-none rounded-[6px] border border-input bg-background py-1 pl-3 pr-8 text-sm ring-offset-background focus:rounded-[6px] focus:outline-none focus:ring-1 focus:ring-ring"
                >
                  {Array.from({ length: 60 }, (_, i) => (
                    <option key={i} value={i.toString().padStart(2, "0")}>
                      {i.toString().padStart(2, "0")}
                    </option>
                  ))}
                </select>
                <ChevronDown className="pointer-events-none absolute right-2 top-1/2 size-4 -translate-y-1/2 opacity-50" />
              </div>
            </div>
            <div className="relative">
              <div className="relative">
                <select
                  value={period}
                  onChange={(e) => handleTimeChange("period", e.target.value)}
                  className="h-9 cursor-pointer appearance-none rounded-[6px] border border-input bg-background py-1 pl-3 pr-8 text-sm ring-offset-background focus:rounded-[6px] focus:outline-none focus:ring-1 focus:ring-ring"
                >
                  <option value="AM">AM</option>
                  <option value="PM">PM</option>
                </select>
                <ChevronDown className="pointer-events-none absolute right-2 top-1/2 size-4 -translate-y-1/2 opacity-50" />
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

Calendar.displayName = "Calendar";

export { Calendar };
