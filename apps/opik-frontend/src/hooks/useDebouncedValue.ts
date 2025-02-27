import { debounce } from "lodash";
import { useCallback, useEffect, useMemo, useState } from "react";

type UseDebouncedValueArgs<TData> = {
  initialValue: TData;
  onDebouncedChange: (value: TData) => void;
  delay?: number;
  onChangeTriggered?: () => void;
};
export const useDebouncedValue = <TData>({
  initialValue,
  onDebouncedChange,
  delay = 300,
  onChangeTriggered,
}: UseDebouncedValueArgs<TData>) => {
  const [inputValue, setInputValue] = useState<TData>(initialValue);

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
      const newValue = event.target.value as TData;
      setInputValue(newValue);
      debouncedCallback(newValue);
      onChangeTriggered?.();
    },
    [debouncedCallback],
  );

  return {
    value: inputValue,
    onChange: handleInputChange,
  };
};
