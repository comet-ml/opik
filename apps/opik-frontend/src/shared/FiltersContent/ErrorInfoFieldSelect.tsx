import React from "react";

import SelectBox from "@/shared/SelectBox/SelectBox";
import { DropdownOption } from "@/types/shared";

const ALL_ERROR_INFO_FIELDS = "__all_error_info_fields__";

const ERROR_INFO_FIELD_OPTIONS: DropdownOption<string>[] = [
  { value: ALL_ERROR_INFO_FIELDS, label: "All fields" },
  { value: "exceptionType", label: "Exception type" },
  { value: "message", label: "Message" },
  { value: "traceback", label: "Traceback" },
];

type ErrorInfoFieldSelectProps = {
  value?: string;
  onValueChange: (value: string) => void;
  className?: string;
  placeholder?: string;
  disabled?: boolean;
  "data-testid"?: string;
};

const ErrorInfoFieldSelect: React.FC<ErrorInfoFieldSelectProps> = ({
  value,
  onValueChange,
  className,
  placeholder = "All fields",
  disabled,
  "data-testid": dataTestId = "filter-error-info-field",
}) => {
  return (
    <SelectBox
      className={className}
      value={value || ALL_ERROR_INFO_FIELDS}
      options={ERROR_INFO_FIELD_OPTIONS}
      placeholder={placeholder}
      disabled={disabled}
      testId={dataTestId}
      onChange={(newValue) =>
        onValueChange(newValue === ALL_ERROR_INFO_FIELDS ? "" : newValue)
      }
    />
  );
};

export default ErrorInfoFieldSelect;
