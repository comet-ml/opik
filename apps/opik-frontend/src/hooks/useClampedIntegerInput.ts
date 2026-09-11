import { useCallback, useEffect, useRef, useState } from "react";

interface UseClampedInputOptions {
  value: number;
  min: number;
  max: number;
  onCommit: (clamped: number) => void;
}

interface UseClampedInputResult {
  displayValue: string;
  isInvalid: boolean;
  onChange: (e: React.ChangeEvent<HTMLInputElement>) => void;
  onFocus: () => void;
  onBlur: () => void;
  onKeyDown: (e: React.KeyboardEvent<HTMLInputElement>) => void;
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(Math.max(min, value), max);
}

export function useClampedIntegerInput({
  value,
  min,
  max,
  onCommit,
}: UseClampedInputOptions): UseClampedInputResult {
  const [localValue, setLocalValue] = useState<string>(String(value));
  const isFocusedRef = useRef(false);

  useEffect(() => {
    if (!isFocusedRef.current) {
      setLocalValue(String(value));
    }
  }, [value]);

  const onChange = useCallback((e: React.ChangeEvent<HTMLInputElement>) => {
    isFocusedRef.current = true;
    setLocalValue(e.target.value);
  }, []);

  const onFocus = useCallback(() => {
    isFocusedRef.current = true;
  }, []);

  const commitValue = useCallback(() => {
    isFocusedRef.current = false;
    const parsed = parseInt(localValue, 10);
    const clamped = Number.isNaN(parsed) ? min : clamp(parsed, min, max);
    setLocalValue(String(clamped));
    onCommit(clamped);
  }, [localValue, min, max, onCommit]);

  const onBlur = useCallback(() => {
    commitValue();
  }, [commitValue]);

  const onKeyDown = useCallback(
    (e: React.KeyboardEvent<HTMLInputElement>) => {
      if (e.key === "Enter") {
        commitValue();
        (e.target as HTMLInputElement).blur();
      }
    },
    [commitValue],
  );

  const parsed = parseInt(localValue, 10);
  const isInvalid =
    localValue.trim() === "" ||
    Number.isNaN(parsed) ||
    parsed < min ||
    parsed > max;

  return {
    displayValue: localValue,
    isInvalid,
    onChange,
    onFocus,
    onBlur,
    onKeyDown,
  };
}
