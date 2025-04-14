import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import debounce from "lodash/debounce";
import isFunction from "lodash/isFunction";
import { Input, InputProps } from "@/components/ui/input";

type InputValue = string | number | readonly string[] | undefined;
type OnValueChangeCallback = (value: InputValue) => void;

export interface DebounceInputProps extends Omit<InputProps, "onChange"> {
  delay?: number;
  onValueChange: OnValueChangeCallback;
}

const DebounceInput = React.forwardRef<HTMLInputElement, DebounceInputProps>(
  ({ value, onValueChange, delay = 300, ...props }, ref) => {
    const [localValue, setLocalValue] = useState<InputValue>(value || "");
    const { onFocus, onBlur } = props;

    const isFocusedRef = useRef(false);
    const pendingValueRef = useRef<InputValue>(undefined);
    const valueChangeCallbackRef = useRef<OnValueChangeCallback | undefined>(
      onValueChange,
    );

    useEffect(() => {
      valueChangeCallbackRef.current = onValueChange;
    }, [onValueChange]);

    const handleDebouncedValueChange = useMemo(
      () =>
        debounce((val: InputValue) => {
          isFunction(valueChangeCallbackRef.current) &&
            valueChangeCallbackRef.current(val);
        }, delay),
      [delay],
    );

    useEffect(() => {
      if (!isFocusedRef.current) {
        setLocalValue(value);
      } else {
        pendingValueRef.current = value;
      }
    }, [value]);

    useEffect(() => {
      return () => {
        handleDebouncedValueChange.cancel();
      };
    }, [handleDebouncedValueChange]);

    const handleChange = useCallback(
      (event: React.ChangeEvent<HTMLInputElement>) => {
        const newValue = event.target.value;
        setLocalValue(newValue);
        handleDebouncedValueChange(newValue);
      },
      [handleDebouncedValueChange],
    );

    const handleFocus = useCallback(
      (e: React.FocusEvent<HTMLInputElement>) => {
        isFocusedRef.current = true;
        onFocus?.(e);
      },
      [onFocus],
    );

    const handleBlur = useCallback(
      (e: React.FocusEvent<HTMLInputElement>) => {
        isFocusedRef.current = false;

        handleDebouncedValueChange.flush();

        setLocalValue((state) => {
          if (state !== pendingValueRef.current) {
            const newValue = pendingValueRef.current;
            pendingValueRef.current = undefined;
            return newValue;
          }

          return state;
        });

        onBlur?.(e);
      },
      [handleDebouncedValueChange, onBlur],
    );

    const displayValue = localValue ?? value ?? "";

    return (
      <Input
        ref={ref}
        {...props}
        value={displayValue}
        onChange={handleChange}
        onFocus={handleFocus}
        onBlur={handleBlur}
      />
    );
  },
);

DebounceInput.displayName = "DebounceInput";

export default DebounceInput;
