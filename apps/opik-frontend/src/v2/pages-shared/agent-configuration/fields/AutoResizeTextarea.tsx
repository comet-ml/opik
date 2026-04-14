import React, { useCallback, useEffect, useRef } from "react";

import { cn } from "@/lib/utils";

type AutoResizeTextareaProps = {
  value: string;
  onChange: (value: string) => void;
  className?: string;
  readOnly?: boolean;
};

const AutoResizeTextarea: React.FC<AutoResizeTextareaProps> = ({
  value,
  onChange,
  className,
  readOnly,
}) => {
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  const resize = useCallback(() => {
    const el = textareaRef.current;
    if (el) {
      el.style.height = "auto";
      el.style.height = el.scrollHeight + "px";
    }
  }, []);

  useEffect(() => {
    resize();
  }, [value, resize]);

  const handleChange = useCallback(
    (e: React.ChangeEvent<HTMLTextAreaElement>) => {
      onChange(e.target.value);
    },
    [onChange],
  );

  return (
    <textarea
      ref={textareaRef}
      className={cn(
        "comet-body-s w-full resize-none overflow-hidden bg-transparent text-foreground outline-none",
        className,
      )}
      value={value}
      onChange={handleChange}
      readOnly={readOnly}
    />
  );
};

export default AutoResizeTextarea;
