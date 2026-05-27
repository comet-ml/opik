import React, { forwardRef, useCallback } from "react";
import TextareaAutosize from "react-textarea-autosize";

import { cn } from "@/lib/utils";

type AutoResizeTextareaProps = {
  value: string;
  onChange: (value: string) => void;
  className?: string;
  readOnly?: boolean;
  placeholder?: string;
};

const AutoResizeTextarea = forwardRef<
  HTMLTextAreaElement,
  AutoResizeTextareaProps
>(({ value, onChange, className, readOnly, placeholder }, forwardedRef) => {
  const handleChange = useCallback(
    (e: React.ChangeEvent<HTMLTextAreaElement>) => {
      onChange(e.target.value);
    },
    [onChange],
  );

  return (
    <TextareaAutosize
      ref={forwardedRef}
      className={cn(
        "comet-body-s w-full resize-none overflow-hidden bg-transparent text-foreground outline-none placeholder:text-light-slate",
        className,
      )}
      value={value}
      onChange={handleChange}
      readOnly={readOnly}
      placeholder={placeholder}
    />
  );
});
AutoResizeTextarea.displayName = "AutoResizeTextarea";

export default AutoResizeTextarea;
