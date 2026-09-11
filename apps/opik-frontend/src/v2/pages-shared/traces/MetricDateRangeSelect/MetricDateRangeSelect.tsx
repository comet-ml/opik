import React from "react";
import { cn } from "@/lib/utils";
import { DateRangeSelect, DateRangeValue } from "@/shared/DateRangeSelect";
import {
  DATE_RANGE_PRESET_PAST_24_HOURS,
  DATE_RANGE_PRESET_PAST_3_DAYS,
  DATE_RANGE_PRESET_PAST_7_DAYS,
  DATE_RANGE_PRESET_PAST_30_DAYS,
  DATE_RANGE_PRESET_PAST_60_DAYS,
  DATE_RANGE_PRESET_ALLTIME,
} from "./constants";

type MetricDateRangeSelectProps = {
  value: DateRangeValue;
  onChangeValue: (range: DateRangeValue) => void;
  minDate?: Date;
  maxDate?: Date;
  hideAlltime?: boolean;
  // Denser surfaces (the trace-logs overlay) size their controls at 24px rather than the 28px
  // pages use, so they override the trigger height.
  triggerClassName?: string;
};

const MetricDateRangeSelect: React.FC<MetricDateRangeSelectProps> = ({
  value,
  onChangeValue,
  minDate,
  maxDate,
  hideAlltime = false,
  triggerClassName,
}) => {
  return (
    <DateRangeSelect
      value={value}
      onChangeValue={onChangeValue}
      minDate={minDate}
      maxDate={maxDate}
    >
      <DateRangeSelect.Trigger className={cn("h-7", triggerClassName)} />
      <DateRangeSelect.Content>
        <DateRangeSelect.PresetOption value={DATE_RANGE_PRESET_PAST_24_HOURS} />
        <DateRangeSelect.PresetOption value={DATE_RANGE_PRESET_PAST_3_DAYS} />
        <DateRangeSelect.PresetOption value={DATE_RANGE_PRESET_PAST_7_DAYS} />
        <DateRangeSelect.PresetOption value={DATE_RANGE_PRESET_PAST_30_DAYS} />
        <DateRangeSelect.PresetOption value={DATE_RANGE_PRESET_PAST_60_DAYS} />
        {!hideAlltime && (
          <DateRangeSelect.PresetOption value={DATE_RANGE_PRESET_ALLTIME} />
        )}
        <DateRangeSelect.CustomDatesOption />
      </DateRangeSelect.Content>
    </DateRangeSelect>
  );
};

export default MetricDateRangeSelect;
