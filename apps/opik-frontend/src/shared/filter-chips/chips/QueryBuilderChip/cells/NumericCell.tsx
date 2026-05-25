import React, { useEffect, useRef } from "react";
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
    <input
      ref={ref}
      type="number"
      step="any"
      value={value}
      placeholder={placeholder}
      onChange={(event) => onChange(event.target.value)}
      onBlur={onBlur}
      className={cn(cellInput, grow && "flex-1", className)}
    />
  );
};
