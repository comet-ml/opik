import React from "react";
import { Clock } from "lucide-react";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { TimeInterval } from "@/types/dashboards";

interface IntervalSelectorProps {
  value: TimeInterval;
  onChange: (value: TimeInterval) => void;
}

const INTERVAL_OPTIONS = [
  { value: "HOURLY" as const, label: "Hourly", description: "View data by hour" },
  { value: "DAILY" as const, label: "Daily", description: "View data by day" },
  { value: "WEEKLY" as const, label: "Weekly", description: "View data by week" },
];

const IntervalSelector: React.FC<IntervalSelectorProps> = ({
  value,
  onChange,
}) => {
  return (
    <div className="flex items-center gap-2">
      <Clock className="size-4 text-muted-foreground" />
      <Select value={value} onValueChange={onChange}>
        <SelectTrigger className="w-[140px]">
          <SelectValue placeholder="Select interval" />
        </SelectTrigger>
        <SelectContent>
          {INTERVAL_OPTIONS.map((option) => (
            <SelectItem key={option.value} value={option.value} title={option.description}>
              {option.label}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
    </div>
  );
};

export default IntervalSelector;

