import React, { useState, useRef, useEffect } from "react";
import { ChevronDown, ChevronUp, Search, X } from "lucide-react";
import isUndefined from "lodash/isUndefined";

import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import DebounceInput from "@/components/shared/DebounceInput/DebounceInput";
import { Separator } from "@/components/ui/separator";

type ExpandableSearchInputProps = {
  placeholder?: string;
  value?: string;
  onChange?: (value: string) => void;
  className?: string;
  disabled?: boolean;
  buttonClassName?: string;
  inputClassName?: string;
  onPrev?: () => void;
  onNext?: () => void;
  currentMatchIndex?: number;
  totalMatches?: number;
};

const ExpandableSearchInput: React.FC<ExpandableSearchInputProps> = ({
  placeholder = "Search...",
  value,
  onChange,
  className,
  buttonClassName,
  inputClassName,
  disabled = false,
  onPrev,
  onNext,
  currentMatchIndex,
  totalMatches,
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
    if (e.key === "ArrowUp" && onPrev) {
      onPrev();
    }
    if (e.key === "ArrowDown" && onNext) {
      onNext();
    }
  };

  const hasMatches = Boolean(totalMatches);

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
          className={buttonClassName}
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
              className={cn("h-8 px-8", inputClassName, {
                "pr-[112px]": hasMatches,
              })}
            />
            <div className="absolute inset-y-0 right-1 flex h-full items-center justify-center gap-0.5">
              {hasMatches && (
                <>
                  {
                    <span className="text-xs text-light-slate">
                      {currentMatchIndex}/{totalMatches}
                    </span>
                  }
                  <Separator orientation="vertical" className="mx-2 h-3" />
                  <Button
                    variant="ghost"
                    size="icon-sm"
                    onClick={onPrev}
                    className="w-4 text-light-slate"
                  >
                    <ChevronUp />
                  </Button>
                  <Button
                    variant="ghost"
                    size="icon-sm"
                    onClick={onNext}
                    className="w-4 text-light-slate"
                  >
                    <ChevronDown />
                  </Button>
                </>
              )}
              <Button
                variant="ghost"
                size="icon-sm"
                onClick={handleCollapse}
                className={cn("text-light-slate", {
                  "w-4 mr-1": hasMatches,
                })}
              >
                <X />
              </Button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default ExpandableSearchInput;
