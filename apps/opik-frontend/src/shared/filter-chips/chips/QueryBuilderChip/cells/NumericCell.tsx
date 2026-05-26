import React, { useEffect, useRef } from "react";
import DebounceInput from "@/shared/DebounceInput/DebounceInput";
import { cn } from "@/lib/utils";
import { cellInput } from "./cellBase";

interface NumericCellProps {
  value: string;
  placeholder?: string;
  onChange: (next: string) => void;
  onBlur?: (event: React.FocusEvent<HTMLInputElement>) => void;
  autoFocus?: boolean;
  grow?: boolean;
  className?: string;
}

export const NumericCell: React.FC<NumericCellProps> = ({
  value,
  placeholder,
  onChange,
  onBlur,
  autoFocus = false,
  grow = false,
  className,
}) => {
  const ref = useRef<HTMLInputElement>(null);
  useEffect(() => {
    if (autoFocus) ref.current?.focus();
  }, [autoFocus]);
  return (
    <DebounceInput
      ref={ref}
      type="number"
      step="any"
      variant="unstyled"
      dimension="none"
      value={value}
      placeholder={placeholder}
      onValueChange={(next) => onChange(String(next ?? ""))}
      onBlur={onBlur}
      className={cn(cellInput, grow && "flex-1", className)}
    />
  );
};
