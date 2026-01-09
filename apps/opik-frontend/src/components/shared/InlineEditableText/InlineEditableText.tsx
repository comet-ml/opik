import React, { useState, useRef, useEffect, useCallback } from "react";
import { Pencil, Check, X, RotateCcw } from "lucide-react";
import { cn } from "@/lib/utils";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

type InlineEditableTextProps = {
  value: string;
  placeholder: string;
  defaultValue?: string;
  onChange: (value: string) => void;
  className?: string;
  isTitle?: boolean;
  rightIcon?: React.ReactNode;
};

const InlineEditableText: React.FunctionComponent<InlineEditableTextProps> = ({
  value,
  placeholder,
  defaultValue,
  onChange,
  className,
  isTitle = false,
  rightIcon,
}) => {
  const [isEditing, setIsEditing] = useState(false);
  const [editValue, setEditValue] = useState(value);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    setEditValue(value);
  }, [value]);

  useEffect(() => {
    if (isEditing && inputRef.current) {
      inputRef.current.focus();
      inputRef.current.select();
    }
  }, [isEditing]);

  const handleStartEdit = useCallback(() => {
    setEditValue(value);
    setIsEditing(true);
  }, [value]);

  const handleSave = useCallback(() => {
    const trimmedValue = editValue.trim();
    const valueToSave = trimmedValue === defaultValue ? "" : trimmedValue;
    onChange(valueToSave);
    setIsEditing(false);
  }, [editValue, defaultValue, onChange]);

  const handleCancel = useCallback(() => {
    setEditValue(value);
    setIsEditing(false);
  }, [value]);

  const handleReset = useCallback(() => {
    if (defaultValue !== undefined) {
      setEditValue(defaultValue);
      onChange("");
      setIsEditing(false);
    }
  }, [defaultValue, onChange]);

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent<HTMLInputElement>) => {
      if (e.key === "Enter") {
        e.preventDefault();
        handleSave();
      } else if (e.key === "Escape") {
        e.preventDefault();
        handleCancel();
      }
    },
    [handleSave, handleCancel],
  );

  const displayValue = value || placeholder;
  const showResetButton =
    defaultValue !== undefined && editValue !== defaultValue;

  // Edit mode
  if (isEditing) {
    return (
      <div
        className={cn(
          "flex h-8 items-center gap-1 overflow-hidden rounded-md",
          className,
        )}
      >
        <div className="flex min-w-0 flex-1 items-center">
          <div className="flex min-w-20 flex-1 flex-col items-start gap-1">
            <div className="flex h-8 w-full items-center justify-between rounded-md border border-primary bg-background p-2">
              <input
                ref={inputRef}
                type="text"
                value={editValue}
                onChange={(e) => setEditValue(e.target.value)}
                onKeyDown={handleKeyDown}
                onBlur={handleSave}
                placeholder={placeholder}
                className="min-w-0 flex-1 truncate bg-transparent text-sm leading-5 text-foreground outline-none"
              />
            </div>
          </div>
        </div>
        <div className="flex h-full items-center pr-2">
          <TooltipWrapper content="Save">
            <button
              type="button"
              onMouseDown={(e) => {
                e.preventDefault();
                handleSave();
              }}
              className="flex size-7 items-center justify-center rounded"
            >
              <Check className="size-3.5 text-special-button" />
            </button>
          </TooltipWrapper>
          <TooltipWrapper content="Cancel">
            <button
              type="button"
              onMouseDown={(e) => {
                e.preventDefault();
                handleCancel();
              }}
              className="flex size-7 items-center justify-center rounded"
            >
              <X className="size-3.5 text-destructive" />
            </button>
          </TooltipWrapper>
          {showResetButton && (
            <>
              <div className="flex h-full items-center px-1 py-2.5">
                <div className="h-full w-px bg-border" />
              </div>
              <TooltipWrapper content="Reset to default">
                <button
                  type="button"
                  onMouseDown={(e) => {
                    e.preventDefault();
                    inputRef.current?.focus();
                  }}
                  onClick={handleReset}
                  className="flex size-7 items-center justify-center rounded"
                >
                  <RotateCcw className="size-3.5 text-foreground" />
                </button>
              </TooltipWrapper>
            </>
          )}
        </div>
      </div>
    );
  }

  // Default/Hover state
  return (
    <TooltipWrapper content={displayValue} side="top">
      <div
        className={cn(
          "group/inline-edit flex h-8 cursor-pointer items-center gap-1 overflow-hidden rounded-md transition-colors hover:bg-muted",
          className,
        )}
        onClick={handleStartEdit}
        role="button"
        tabIndex={0}
        onKeyDown={(e) => {
          if (e.key === "Enter" || e.key === " ") {
            e.preventDefault();
            handleStartEdit();
          }
        }}
      >
        <div className="flex min-w-0 items-center gap-1.5 pl-2">
          <span
            className={cn(
              "block truncate text-sm leading-5",
              isTitle ? "font-medium text-foreground" : "text-foreground",
              !value && "text-muted-slate",
            )}
          >
            {displayValue}
          </span>
          {rightIcon && (
            <div className="flex shrink-0 items-center">{rightIcon}</div>
          )}
        </div>
        <div className="ml-auto hidden h-full items-center pr-2 group-hover/inline-edit:flex">
          <div className="flex size-7 items-center justify-center rounded">
            <Pencil className="size-3.5 text-foreground" />
          </div>
        </div>
      </div>
    </TooltipWrapper>
  );
};

export default InlineEditableText;
