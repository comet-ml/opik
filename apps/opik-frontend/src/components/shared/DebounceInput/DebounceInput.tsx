import * as React from "react";
import { Input, InputProps } from "@/components/ui/input";
import { useCallback, useEffect, useMemo, useState } from "react";
import debounce from "lodash/debounce";

type InputValue = string | number | readonly string[] | undefined;

export interface DebounceInputProps extends Omit<InputProps, "onChange"> {
  delay?: number;
  onValueChange: (value: InputValue) => void;
}

const DebounceInput = React.forwardRef<HTMLInputElement, DebounceInputProps>(
  ({ value, onValueChange, delay = 300, ...props }, ref) => {
    const [localValue, setLocalValue] = useState<InputValue>(undefined);

    const handleDebouncedValueChange = useMemo(
      () => debounce(onValueChange, delay),
      [delay, onValueChange],
    );

    useEffect(() => {
      setLocalValue(value);
      return () => handleDebouncedValueChange.cancel();
    }, [handleDebouncedValueChange, value]);

    const handleChange = useCallback(
      (event: React.ChangeEvent<HTMLInputElement>) => {
        const value = event.target.value;

        setLocalValue(value);
        handleDebouncedValueChange(value);
      },
      [handleDebouncedValueChange],
    );

    return (
      <Input
        ref={ref}
        {...props}
        value={localValue ?? value}
        onChange={handleChange}
      />
    );
  },
);

DebounceInput.displayName = "DebounceInput";

export default DebounceInput;
