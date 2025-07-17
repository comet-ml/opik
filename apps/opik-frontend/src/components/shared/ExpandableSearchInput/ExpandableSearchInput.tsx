import React, { useState, useRef, useEffect } from "react";
import { ChevronDown, ChevronUp, Search, X } from "lucide-react";
import isUndefined from "lodash/isUndefined";

import { cn } from "@/lib/utils";
import { Button, buttonVariants } from "@/components/ui/button";
import DebounceInput from "@/components/shared/DebounceInput/DebounceInput";
import { cva, VariantProps } from "class-variance-authority";

const searchInputVariants = cva("px-8", {
  variants: {
    size: {
      default: "h-8",
      sm: "h-7",
    },
  },
  defaultVariants: {
    size: "default",
  },
});

type ExpandableSearchInputProps = {
  placeholder?: string;
  value?: string;
  onChange?: (value: string) => void;
  className?: string;
  disabled?: boolean;
  buttonVariant?: VariantProps<typeof buttonVariants>["variant"];
  size?: VariantProps<typeof searchInputVariants>["size"];
  onPrev?: () => void;
  onNext?: () => void;
};

const ExpandableSearchInput: React.FC<ExpandableSearchInputProps> = ({
  placeholder = "Search...",
  value,
  onChange,
  className,
  buttonVariant = "outline",
  size = "default",
  disabled = false,
  onPrev,
  onNext,
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
      e.preventDefault();
      onPrev();
    }
    if (e.key === "ArrowDown" && onNext) {
      e.preventDefault();
      onNext();
    }
  };

  const hasNextPrev = Boolean(onPrev || onNext);

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
          variant={buttonVariant}
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
              className={cn(searchInputVariants({ size }), {
                "pr-[60px]": hasNextPrev,
              })}
            />
            <div className="absolute inset-y-0 right-1 flex h-full items-center justify-center gap-0.5">
              {hasNextPrev && (
                <>
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
                  "w-4 mr-1": hasNextPrev,
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
