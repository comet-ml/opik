import { debounce } from "lodash";
import { useCallback, useEffect, useMemo, useState } from "react";

type UseDebouncedValueArgs = {
  initialValue?: string;
  onDebouncedChange: (value: string) => void;
  delay?: number;
  onChangeTriggered?: () => void;
};
export const useDebouncedValue = ({
  initialValue,
  onDebouncedChange,
  delay = 300,
  onChangeTriggered,
}: UseDebouncedValueArgs) => {
  const [inputValue, setInputValue] = useState<string | undefined>(
    initialValue,
  );

  const debouncedCallback = useMemo(
    () => debounce(onDebouncedChange, delay),
    [delay, onDebouncedChange],
  );

  useEffect(() => {
    setInputValue(initialValue);
    return () => debouncedCallback.cancel();
  }, [debouncedCallback, initialValue]);

  const handleInputChange = useCallback(
    (event: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) => {
      const newValue = event.target.value;
      setInputValue(newValue);
      debouncedCallback(newValue);
      onChangeTriggered?.();
    },
    [debouncedCallback, onChangeTriggered],
  );

  const onReset = useCallback(() => {
    setInputValue("");
    debouncedCallback("");
    onChangeTriggered?.();
  }, [debouncedCallback, onChangeTriggered]);

  return {
    value: inputValue,
    onChange: handleInputChange,
    onReset,
  };
};
