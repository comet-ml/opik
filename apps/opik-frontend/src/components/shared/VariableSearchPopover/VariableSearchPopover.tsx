import React, {
  useState,
  useCallback,
  useEffect,
  useRef,
  useMemo,
} from "react";
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover";
import { Tag } from "@/components/ui/tag";
import { cn } from "@/lib/utils";
import { JsonObject, JsonValue } from "@/types/shared";
import { getValuePreview } from "@/components/shared/JsonTreePopover/jsonTreeUtils";

interface VariableSearchPopoverProps {
  data: JsonObject;
  onSelect: (path: string, value: JsonValue) => void;
  trigger: React.ReactNode;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  searchQuery?: string;
  onFocusedKeyChange?: (key: string | null) => void;
}

const MAX_HEIGHT = "320px";

const getValueDisplay = (value: JsonValue): string => {
  if (value === null) return "-";
  if (typeof value === "string") return value || "-";
  if (typeof value === "number" || typeof value === "boolean")
    return String(value);
  return getValuePreview(value);
};

const VariableSearchPopover: React.FC<VariableSearchPopoverProps> = ({
  data,
  onSelect,
  trigger,
  open,
  onOpenChange,
  searchQuery = "",
  onFocusedKeyChange,
}) => {
  const [focusedIndex, setFocusedIndex] = useState<number>(-1);
  const contentRef = useRef<HTMLDivElement>(null);

  const entries = useMemo(() => Object.entries(data), [data]);

  const hasSampleValues = useMemo(
    () => entries.some(([, value]) => value !== ""),
    [entries],
  );

  const filteredEntries = useMemo(() => {
    if (!searchQuery.trim()) return entries;
    const query = searchQuery.toLowerCase();
    return entries.filter(([key]) => key.toLowerCase().includes(query));
  }, [entries, searchQuery]);

  useEffect(() => {
    if (open) {
      setFocusedIndex(filteredEntries.length > 0 ? 0 : -1);
    }
  }, [open, filteredEntries.length]);

  useEffect(() => {
    if (focusedIndex >= filteredEntries.length) {
      setFocusedIndex(filteredEntries.length > 0 ? 0 : -1);
    }
  }, [filteredEntries.length, focusedIndex]);

  useEffect(() => {
    const key =
      focusedIndex >= 0 && focusedIndex < filteredEntries.length
        ? filteredEntries[focusedIndex][0]
        : null;
    onFocusedKeyChange?.(key);
  }, [focusedIndex, filteredEntries, onFocusedKeyChange]);

  const handleSelect = useCallback(
    (key: string, value: JsonValue) => {
      onSelect(key, value);
      onOpenChange(false);
    },
    [onSelect, onOpenChange],
  );

  useEffect(() => {
    if (!open) return;

    const handleKeyDown = (e: KeyboardEvent) => {
      switch (e.key) {
        case "ArrowDown": {
          e.preventDefault();
          e.stopPropagation();
          setFocusedIndex((prev) =>
            prev < filteredEntries.length - 1 ? prev + 1 : 0,
          );
          break;
        }
        case "ArrowUp": {
          e.preventDefault();
          e.stopPropagation();
          setFocusedIndex((prev) =>
            prev > 0 ? prev - 1 : filteredEntries.length - 1,
          );
          break;
        }
        case "Tab": {
          e.preventDefault();
          e.stopPropagation();
          setFocusedIndex((prev) => {
            if (e.shiftKey) {
              return prev > 0 ? prev - 1 : filteredEntries.length - 1;
            }
            return prev < filteredEntries.length - 1 ? prev + 1 : 0;
          });
          break;
        }
        case "Enter": {
          if (focusedIndex >= 0 && focusedIndex < filteredEntries.length) {
            e.preventDefault();
            e.stopPropagation();
            const [key, value] = filteredEntries[focusedIndex];
            handleSelect(key, value);
          }
          break;
        }
        case "Escape": {
          e.preventDefault();
          e.stopPropagation();
          onOpenChange(false);
          break;
        }
      }
    };

    document.addEventListener("keydown", handleKeyDown, true);
    return () => document.removeEventListener("keydown", handleKeyDown, true);
  }, [open, focusedIndex, filteredEntries, handleSelect, onOpenChange]);

  useEffect(() => {
    if (focusedIndex >= 0 && contentRef.current) {
      const item = contentRef.current.children[focusedIndex] as HTMLElement;
      item?.scrollIntoView({ block: "nearest" });
    }
  }, [focusedIndex]);

  return (
    <Popover open={open} onOpenChange={onOpenChange}>
      <PopoverTrigger asChild>{trigger}</PopoverTrigger>
      <PopoverContent
        className="w-2/3 min-w-[470px] max-w-[600px] p-0"
        align="start"
        side="bottom"
        onOpenAutoFocus={(e) => e.preventDefault()}
        collisionPadding={16}
        sideOffset={4}
      >
        <div className="flex items-start justify-between border-b px-3 py-2">
          <div>
            <p className="comet-body-s-accented">Search variables</p>
            {hasSampleValues && (
              <p className="comet-body-xs text-light-slate">
                Preview values are samples from the dataset
              </p>
            )}
          </div>
          <span className="comet-body-xs shrink-0 text-light-slate">
            {entries.length} available
          </span>
        </div>

        <div
          ref={contentRef}
          className="overflow-auto p-0"
          style={{ maxHeight: MAX_HEIGHT }}
        >
          {filteredEntries.length === 0 ? (
            <div className="px-3 py-4 text-center text-sm text-muted-foreground">
              No matching variables found
            </div>
          ) : (
            filteredEntries.map(([key, value], index) => (
              <button
                key={key}
                className={cn(
                  "flex w-full items-center rounded-sm p-2 text-left text-sm",
                  index === focusedIndex
                    ? "bg-muted"
                    : "hover:bg-muted/50",
                )}
                onClick={() => handleSelect(key, value)}
                onMouseEnter={() => setFocusedIndex(index)}
              >
                <Tag variant="green" className="shrink-0" size="lg">
                  {key}
                </Tag>
                {hasSampleValues && (
                  <span className="comet-body-xs truncate text-light-slate">
                    {getValueDisplay(value)}
                  </span>
                )}
              </button>
            ))
          )}
        </div>
      </PopoverContent>
    </Popover>
  );
};

export default VariableSearchPopover;
