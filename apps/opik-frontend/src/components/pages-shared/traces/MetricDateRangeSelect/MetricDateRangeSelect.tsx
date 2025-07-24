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
};

const MetricDateRangeSelect: React.FC<MetricDateRangeSelectProps> = ({
  value,
  onChangeValue,
  minDate,
  maxDate,
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
        <DateRangeSelect.PresetOption preset="today" />
        <DateRangeSelect.PresetOption preset="last3days" />
        <DateRangeSelect.PresetOption preset="lastWeek" />
        <DateRangeSelect.PresetOption preset="lastMonth" />
        <DateRangeSelect.CustomDatesOption />
      </DateRangeSelect.Content>
    </DateRangeSelect>
  );
};

export default MetricDateRangeSelect;
