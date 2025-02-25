import * as React from "react";
import { Input, InputProps } from "@/components/ui/input";
import { useDebouncedValue } from "@/hooks/useDebouncedValue";

type InputValue = string | number | readonly string[] | undefined;

export interface DebounceInputProps extends Omit<InputProps, "onChange"> {
  delay?: number;
  onValueChange: (value: InputValue) => void;
}

const DebounceInput = React.forwardRef<HTMLInputElement, DebounceInputProps>(
  ({ value, onValueChange, delay = 300, ...props }, ref) => {
    const { value: localValue, onChange } = useDebouncedValue(
      value,
      onValueChange,
      delay,
    );

    return (
      <Input
        ref={ref}
        {...props}
        value={localValue ?? value}
        onChange={onChange}
      />
    );
  },
);

DebounceInput.displayName = "DebounceInput";

export default DebounceInput;
