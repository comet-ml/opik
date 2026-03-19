import React, { useCallback, useState, useRef, useEffect } from "react";
import { Pencil, Check, X } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";

const MIN_FIELD_WIDTH = 100;
const HORIZONTAL_PADDING = 40;

interface DashboardSectionTitleProps {
  title: string;
  onChange: (title: string) => void;
}

const DashboardSectionTitle: React.FunctionComponent<
  DashboardSectionTitleProps
> = ({ title, onChange }) => {
  const [isEditing, setIsEditing] = useState(false);
  const [editValue, setEditValue] = useState(title);
  const inputRef = useRef<HTMLInputElement>(null);
  const widthMeterRef = useRef<HTMLSpanElement>(null);

  const inputWidth = widthMeterRef.current
    ? Math.max(
        widthMeterRef.current.getBoundingClientRect().width +
          HORIZONTAL_PADDING,
        MIN_FIELD_WIDTH,
      )
    : MIN_FIELD_WIDTH;

  useEffect(() => {
    setEditValue(title);
  }, [title]);

  useEffect(() => {
    if (isEditing && inputRef.current) {
      inputRef.current.focus();
      inputRef.current.select();
    }
  }, [isEditing]);

  const handleStartEdit = useCallback((e: React.MouseEvent) => {
    e.stopPropagation();
    setIsEditing(true);
  }, []);

  const handleSaveEdit = useCallback(() => {
    setIsEditing(false);
    const trimmedValue = editValue.trim();
    if (trimmedValue) {
      if (trimmedValue !== title) {
        onChange(trimmedValue);
      }
    } else {
      setEditValue(title);
    }
  }, [editValue, title, onChange]);

  const handleCancelEdit = useCallback(() => {
    setIsEditing(false);
    setEditValue(title);
  }, [title]);

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (e.key === "Enter") {
        handleSaveEdit();
      } else if (e.key === "Escape") {
        handleCancelEdit();
      }
    },
    [handleSaveEdit, handleCancelEdit],
  );

  return (
    <div className="group/title flex flex-1 items-center gap-1">
      <span
        ref={widthMeterRef}
        className="pointer-events-none absolute left-0 top-0 -z-10 whitespace-pre text-sm opacity-0"
        aria-hidden="true"
      >
        {editValue}
      </span>

      {isEditing ? (
        <>
          <Input
            ref={inputRef}
            value={editValue}
            onChange={(e) => setEditValue(e.target.value)}
            onBlur={handleSaveEdit}
            onKeyDown={handleKeyDown}
            style={{ width: `${inputWidth}px` }}
            className="h-8"
            onClick={(e) => e.stopPropagation()}
          />
          <Button
            variant="ghost"
            size="icon-xs"
            onClick={(e) => {
              e.stopPropagation();
              handleSaveEdit();
            }}
          >
            <Check />
          </Button>
          <Button
            variant="ghost"
            size="icon-xs"
            className="text-destructive hover:text-destructive active:text-destructive"
            onClick={(e) => {
              e.stopPropagation();
              handleCancelEdit();
            }}
          >
            <X />
          </Button>
        </>
      ) : (
        <>
          <span className="text-sm font-medium text-foreground">{title}</span>
          <Button
            variant="ghost"
            size="icon"
            className="size-6 opacity-0 transition-opacity group-hover/title:opacity-100 group-hover:opacity-100"
            onClick={handleStartEdit}
          >
            <Pencil className="size-3.5 text-muted-slate" />
          </Button>
        </>
      )}
    </div>
  );
};

export default DashboardSectionTitle;
