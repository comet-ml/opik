import React from "react";
import DateRangeSelect, {
  DateRangeValue,
  DEFAULT_DATE_RANGE,
} from "@/components/shared/DateRangeSelect/DateRangeNewSelect";

type NewDateRangePickerProps = {
  value?: DateRangeValue;
  onChangeValue?: (range: DateRangeValue) => void;
  minDate?: Date;
  maxDate?: Date;
  placeholder?: string;
  className?: string;
};

const NewDateRangePicker: React.FC<NewDateRangePickerProps> = ({
  value = DEFAULT_DATE_RANGE,
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

export default NewDateRangePicker;
