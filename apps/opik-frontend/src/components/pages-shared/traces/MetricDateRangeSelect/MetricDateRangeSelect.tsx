import React from "react";
import {
  DateRangeSelect,
  DateRangeValue,
} from "@/components/shared/DateRangeSelect";

type MetricDateRangeSelectProps = {
  value: DateRangeValue;
  onChangeValue: (range: DateRangeValue) => void;
  minDate?: Date;
  maxDate?: Date;
  hideAlltime?: boolean;
};

const MetricDateRangeSelect: React.FC<MetricDateRangeSelectProps> = ({
  value,
  onChangeValue,
  minDate,
  maxDate,
  hideAlltime = false,
}) => {
  return (
    <DateRangeSelect
      value={value}
      onChangeValue={onChangeValue}
      minDate={minDate}
      maxDate={maxDate}
    >
      <DateRangeSelect.Trigger />
      <DateRangeSelect.Content>
        <DateRangeSelect.PresetOption value="past24hours" />
        <DateRangeSelect.PresetOption value="past3days" />
        <DateRangeSelect.PresetOption value="past7days" />
        <DateRangeSelect.PresetOption value="past30days" />
        <DateRangeSelect.PresetOption value="past60days" />
        {!hideAlltime && <DateRangeSelect.PresetOption value="alltime" />}
        <DateRangeSelect.CustomDatesOption />
      </DateRangeSelect.Content>
    </DateRangeSelect>
  );
};

export default MetricDateRangeSelect;
