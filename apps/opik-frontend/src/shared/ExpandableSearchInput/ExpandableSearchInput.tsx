import React, { useState, useRef, useEffect } from "react";
import { ChevronDown, ChevronUp, Search, X } from "lucide-react";
import isUndefined from "lodash/isUndefined";

import { cn } from "@/lib/utils";
import { Button, buttonVariants } from "@/ui/button";
import DebounceInput from "@/shared/DebounceInput/DebounceInput";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
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
  tooltip?: string;
  onPrev?: () => void;
  onNext?: () => void;
  onExpandedChange?: (expanded: boolean) => void;
  // Expand as an overlay filling the nearest `relative` ancestor
  overlayExpand?: boolean;
};

const ExpandableSearchInput: React.FC<ExpandableSearchInputProps> = ({
  placeholder = "Search...",
  value,
  onChange,
  className,
  buttonVariant = "outline",
  size = "default",
  disabled = false,
  tooltip,
  onPrev,
  onNext,
  onExpandedChange,
  overlayExpand = false,
}) => {
  const [isExpanded, setIsExpanded] = useState(Boolean(value) && !disabled);
  const inputRef = useRef<HTMLInputElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const overlayRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (overlayRef.current) {
      if (isExpanded) {
        overlayRef.current.removeAttribute("inert");
      } else {
        overlayRef.current.setAttribute("inert", "");
      }
    }
    if (isExpanded && inputRef.current) {
      inputRef.current.focus();
    }
  }, [isExpanded]);

  useEffect(() => {
    if (isUndefined(value)) {
      setIsExpanded(false);
      onExpandedChange?.(false);
    }
  }, [value, onExpandedChange]);

  const handleExpand = () => {
    if (disabled) return;
    setIsExpanded(true);
    onExpandedChange?.(true);
  };

  const handleCollapse = () => {
    setIsExpanded(false);
    onExpandedChange?.(false);
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

  const handleBlur = (e: React.FocusEvent<HTMLDivElement>) => {
    if (inputRef.current?.value) return;
    const next = e.relatedTarget as Node | null;
    if (next && containerRef.current?.contains(next)) return;
    handleCollapse();
  };

  const hasNextPrev = Boolean(onPrev || onNext);

  const inputContent = (
    <div className="relative w-full" onBlur={handleBlur}>
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
  );

  if (overlayExpand) {
    return (
      <div
        ref={containerRef}
        className={cn("flex h-8 w-8 items-center", className)}
      >
        <TooltipWrapper content={tooltip}>
          <Button
            variant={buttonVariant}
            size="icon-sm"
            onClick={handleExpand}
            disabled={disabled}
            className={cn(
              "transition-opacity duration-100",
              isExpanded && "pointer-events-none opacity-0",
            )}
          >
            <Search />
          </Button>
        </TooltipWrapper>
        <div
          ref={overlayRef}
          className={cn(
            "absolute right-0 top-1/2 z-10 h-8 -translate-y-1/2 overflow-hidden transition-[width] duration-200 ease-in-out",
            isExpanded ? "w-full" : "w-0 pointer-events-none",
          )}
        >
          <div
            className={cn(
              "h-full w-full",
              isExpanded && "animate-in fade-in duration-150",
            )}
          >
            {inputContent}
          </div>
        </div>
      </div>
    );
  }

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
        <TooltipWrapper content={tooltip}>
          <Button
            variant={buttonVariant}
            size="icon-sm"
            onClick={handleExpand}
            disabled={disabled}
          >
            <Search />
          </Button>
        </TooltipWrapper>
      )}
      {isExpanded && (
        <div className="relative flex w-full items-center">{inputContent}</div>
      )}
    </div>
  );
};

export default ExpandableSearchInput;
