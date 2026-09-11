import { debounce } from "lodash";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";

type UseDebouncedValueArgs = {
  initialValue?: string;
  onDebouncedChange: (value: string) => void;
  delay?: number;
  onChange?: () => void;
};
export const useDebouncedValue = ({
  initialValue,
  onDebouncedChange,
  delay = 300,
  onChange,
}: UseDebouncedValueArgs) => {
  const [inputValue, setInputValue] = useState<string | undefined>(
    initialValue,
  );

  const isFocusedRef = useRef(false);
  const pendingValueRef = useRef<string | undefined>(undefined);

  const debouncedCallback = useMemo(
    () => debounce(onDebouncedChange, delay),
    [delay, onDebouncedChange],
  );

  useEffect(() => {
    if (!isFocusedRef.current) {
      setInputValue(initialValue);
      pendingValueRef.current = undefined;
    } else {
      pendingValueRef.current = initialValue;
    }
  }, [initialValue]);

  useEffect(() => {
    return () => {
      debouncedCallback.cancel();
    };
  }, [debouncedCallback]);

  const handleInputChange = useCallback(
    (event: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) => {
      const newValue = event.target.value;
      setInputValue(newValue);
      debouncedCallback(newValue);
      onChange?.();
    },
    [debouncedCallback, onChange],
  );

  const handleFocus = useCallback(() => {
    isFocusedRef.current = true;
  }, []);

  const handleBlur = useCallback(() => {
    isFocusedRef.current = false;
    debouncedCallback.flush();
    if (pendingValueRef.current !== undefined) {
      setInputValue(pendingValueRef.current);
      pendingValueRef.current = undefined;
    }
  }, [debouncedCallback]);

  const onReset = useCallback(() => {
    setInputValue("");
    debouncedCallback("");
  }, [debouncedCallback]);

  return {
    value: inputValue,
    setInputValue,
    onChange: handleInputChange,
    onFocus: handleFocus,
    onBlur: handleBlur,
    onReset,
  };
};
