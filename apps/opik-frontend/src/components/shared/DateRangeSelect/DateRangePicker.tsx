import React from "react";
import DateRangeSelect, {
  DateRangeValue,
  DEFAULT_DATE_RANGE,
} from "@/components/shared/DateRangeSelect/DateRangeSelect";
import { Separator } from "@/components/ui/separator";

type DateRangePickerProps = {
  value?: DateRangeValue;
  onChangeValue?: (range: DateRangeValue) => void;
  minDate?: Date;
  maxDate?: Date;
  placeholder?: string;
  className?: string;
};

const DateRangePicker: React.FC<DateRangePickerProps> = ({
  value = DEFAULT_DATE_RANGE,
  onChangeValue,
  minDate,
  maxDate,
  placeholder = "Select date range",
  className,
}) => {
  return (
    <DateRangeSelect
      value={value}
      onChangeValue={onChangeValue}
      minDate={minDate}
      maxDate={maxDate}
    >
      <DateRangeSelect.Popover>
        <DateRangeSelect.Trigger
          className={className}
          placeholder={placeholder}
        />
        <DateRangeSelect.Content>
          <div className="w-[300px]  p-6">
            {/* Header */}
            <div className="comet-title-s mb-2">Pick your date</div>

            {/* Calendar */}
            <div className="mb-2">
              <DateRangeSelect.Calendar className="p-0" />
            </div>

            <Separator />

            {/* Date Inputs */}
            <div className="mt-4 grid grid-cols-2 gap-4">
              <DateRangeSelect.FromDateInput />
              <DateRangeSelect.ToDateInput />
            </div>

            {/* Error Message */}
            <DateRangeSelect.ErrorMessage className="mt-2" />

            <Separator className="mt-4" />

            {/* Quick Preset Buttons */}
            <div className="my-4 space-y-2">
              <DateRangeSelect.QuickButton preset="today" className="w-full">
                Today
              </DateRangeSelect.QuickButton>
              <DateRangeSelect.QuickButton
                preset="last3days"
                className="w-full"
              >
                Last 3 days
              </DateRangeSelect.QuickButton>
              <DateRangeSelect.QuickButton preset="lastWeek" className="w-full">
                Last week
              </DateRangeSelect.QuickButton>
              <DateRangeSelect.QuickButton
                preset="lastMonth"
                className="w-full"
              >
                Last month
              </DateRangeSelect.QuickButton>
            </div>

            {/* Action Buttons */}
            <div className="flex justify-end gap-3 border-t pt-4">
              <DateRangeSelect.CancelButton />
              <DateRangeSelect.ApplyButton />
            </div>
          </div>
        </DateRangeSelect.Content>
      </DateRangeSelect.Popover>
    </DateRangeSelect>
  );
};

export default DateRangePicker;
