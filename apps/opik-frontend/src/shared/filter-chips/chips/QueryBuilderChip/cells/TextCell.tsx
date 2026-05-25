import React, { useEffect, useRef } from "react";
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
    <input
      ref={ref}
      type="text"
      value={value}
      placeholder={placeholder}
      onChange={(event) => onChange(event.target.value)}
      className={cn(cellInput, grow && "flex-1", className)}
    />
  );
};
