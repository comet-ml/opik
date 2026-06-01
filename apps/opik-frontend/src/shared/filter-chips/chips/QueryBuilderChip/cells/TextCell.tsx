import React, { useEffect, useRef } from "react";
import DebounceInput from "@/shared/DebounceInput/DebounceInput";
import { cn } from "@/lib/utils";
import { cellInput } from "./cellBase";

interface TextCellProps {
  value: string;
  placeholder?: string;
  onChange: (next: string) => void;
  autoFocus?: boolean;
  grow?: boolean;
  className?: string;
}

export const TextCell: React.FC<TextCellProps> = ({
  value,
  placeholder,
  onChange,
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
      type="text"
      data-filter-cell
      variant="unstyled"
      dimension="none"
      value={value}
      placeholder={placeholder}
      onValueChange={(next) => onChange(String(next ?? ""))}
      className={cn(cellInput, grow && "flex-1", className)}
    />
  );
};
