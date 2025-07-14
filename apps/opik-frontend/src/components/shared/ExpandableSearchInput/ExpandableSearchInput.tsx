import React, { useState, useRef, useEffect } from "react";
import { Search, X } from "lucide-react";
import isUndefined from "lodash/isUndefined";

import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import DebounceInput from "@/components/shared/DebounceInput/DebounceInput";

type ExpandableSearchInputProps = {
  placeholder?: string;
  value?: string;
  onChange?: (value: string) => void;
  className?: string;
  disabled?: boolean;
};

const ExpandableSearchInput: React.FC<ExpandableSearchInputProps> = ({
  placeholder = "Search...",
  value,
  onChange,
  className,
  disabled = false,
}) => {
  const [isExpanded, setIsExpanded] = useState(Boolean(value) && !disabled);
  const inputRef = useRef<HTMLInputElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (isExpanded && inputRef.current) {
      inputRef.current.focus();
    }
  }, [isExpanded]);

  useEffect(() => {
    setIsExpanded((state) => (isUndefined(value) ? false : state));
  }, [value]);

  const handleExpand = () => {
    if (disabled) return;
    setIsExpanded(true);
  };

  const handleCollapse = () => {
    setIsExpanded(false);
    onChange?.("");
  };

  const handleInputChange = (newValue: string) => {
    onChange?.(newValue);
  };

  const handleKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === "Escape") {
      handleCollapse();
    }
  };

  return (
    <div
      ref={containerRef}
      className={cn(
        "relative flex items-center transition-all duration-200 ease-in-out",
        isExpanded ? "w-full" : "w-8",
        className,
      )}
    >
      {!isExpanded && (
        <Button
          variant="outline"
          size="icon-sm"
          onClick={handleExpand}
          disabled={disabled}
        >
          <Search />
        </Button>
      )}
      {isExpanded && (
        <div className="relative flex w-full items-center">
          <div className="relative w-full">
            <Search className="absolute left-3 top-1/2 size-3 -translate-y-1/2 text-muted-foreground" />
            <DebounceInput
              ref={inputRef}
              placeholder={placeholder}
              value={value}
              onValueChange={(value) => handleInputChange(value as string)}
              onKeyDown={handleKeyDown}
              disabled={disabled}
              className="h-8 px-8"
            />
            <Button
              variant="ghost"
              size="icon-sm"
              onClick={handleCollapse}
              className="absolute right-1 top-1/2 size-6 -translate-y-1/2 text-light-slate"
            >
              <X />
            </Button>
          </div>
        </div>
      )}
    </div>
  );
};

export default ExpandableSearchInput;
