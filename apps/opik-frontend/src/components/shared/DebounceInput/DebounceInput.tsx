import * as React from "react";
import { Input, InputProps } from "@/components/ui/input";
import { useCallback, useEffect, useMemo, useState } from "react";
import debounce from "lodash/debounce";

type InputValue = string | number | readonly string[] | undefined;

export interface DebounceInputProps extends Omit<InputProps, "onChange"> {
  delay?: number;
  onChangeValue: (value: InputValue) => void;
}

const DebounceInput = React.forwardRef<HTMLInputElement, DebounceInputProps>(
  ({ value, onChangeValue, delay = 300, ...props }, ref) => {
    const [localValue, setLocalValue] = useState<InputValue>(undefined);

    useEffect(() => {
      setLocalValue(value);
    }, [value]);

    const handleDebouncedChangeValue = useMemo(
      () => debounce(onChangeValue, delay),
      [delay, onChangeValue],
    );

    const handleChange = useCallback(
      (event: React.ChangeEvent<HTMLInputElement>) => {
        const value = event.target.value;

        setLocalValue(value);
        handleDebouncedChangeValue(value);
      },
      [handleDebouncedChangeValue],
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
