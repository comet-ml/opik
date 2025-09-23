import { debounce } from "lodash";
import { useCallback, useMemo, useState } from "react";

type UseDebouncedValueArgs = {
  initialValue?: string;
  onDebouncedChange: (value: string) => void;
  delay?: number;
  onChange?: () => void;
};
export const useDebouncedValue = ({
  onDebouncedChange,
  delay = 300,
  onChange,
}: UseDebouncedValueArgs) => {
  const [inputValue, setInputValue] = useState<string | undefined>();

  const debouncedCallback = useMemo(
    () => debounce(onDebouncedChange, delay),
    [delay, onDebouncedChange],
  );

  const handleInputChange = useCallback(
    (event: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) => {
      const newValue = event.target.value;
      setInputValue(newValue);
      debouncedCallback(newValue);
      onChange?.();
    },
    [debouncedCallback, onChange],
  );

  const onReset = useCallback(() => {
    setInputValue("");
    debouncedCallback("");
  }, [debouncedCallback]);

  return {
    value: inputValue,
    setInputValue,
    onChange: handleInputChange,
    onReset,
  };
};
