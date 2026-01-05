import React, { useMemo, useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover";
import { Input } from "@/components/ui/input";
import { useToast } from "@/components/ui/use-toast";
import PromptVariablesList from "@/components/pages-shared/llm/PromptVariablesList/PromptVariablesList";

export type MediaType = "image" | "video" | "audio";

export interface AddMediaPopoverProps {
  type: MediaType;
  items: string[];
  setItems: (newItems: string[]) => void;
  maxItems?: number;
  align?: "start" | "end";
  onOpenChange?: (open: boolean) => void;
  promptVariables?: string[];
  children: React.ReactNode;
}

const DEFAULT_MAX_ITEMS: Record<MediaType, number> = {
  image: Infinity,
  video: Infinity,
  audio: Infinity,
};

const isHttpUrl = (value: string): boolean => {
  try {
    const url = new URL(value);
    return url.protocol === "http:" || url.protocol === "https:";
  } catch {
    return false;
  }
};

const validateMediaUrl = (url: string): { valid: boolean; error?: string } => {
  if (!isHttpUrl(url)) {
    // Allow template variables like {{image}} or {{video}}, {{audio}}
    if (url.match(/^\{\{.+\}\}$/)) {
      return { valid: true };
    }
    return { valid: false, error: "Please enter a valid HTTP or HTTPS URL" };
  }
  return { valid: true };
};

const AddMediaPopover: React.FC<AddMediaPopoverProps> = ({
  type,
  items,
  setItems,
  maxItems,
  align = "start",
  onOpenChange,
  promptVariables = [],
  children,
}) => {
  const { toast } = useToast();
  const [open, setOpen] = useState(false);
  const [newItem, setNewItem] = useState<string>("");
  const inputRef = useRef<HTMLInputElement>(null);

  const resolvedMaxItems = maxItems ?? DEFAULT_MAX_ITEMS[type];

  const title = useMemo(() => {
    if (type === "image") {
      return "Add image";
    }
    if (type === "video") {
      return "Add video";
    }
    return "Add audio";
  }, [type]);

  const placeholder = useMemo(() => {
    if (type === "image") {
      return "Enter image URL or template variable";
    }
    if (type === "video") {
      return "Enter video URL or template variable";
    }
    return "Enter audio URL or template variable";
  }, [type]);

  const handleOpenChange = (isOpen: boolean) => {
    setOpen(isOpen);
    onOpenChange?.(isOpen);

    if (!isOpen) {
      setNewItem("");
    }
  };

  const handleVariableClick = (variable: string) => {
    const variableText = `{{${variable}}}`;
    setNewItem(variableText);
    inputRef.current?.focus();
  };

  const handleAddItem = () => {
    const trimmed = newItem.trim();
    if (!trimmed) return;

    if (items.length >= resolvedMaxItems) {
      const typeLabel =
        type === "image" ? "images" : type === "video" ? "videos" : "audios";
      toast({
        title: "Maximum limit reached",
        description: `You can only add up to ${resolvedMaxItems} ${typeLabel}`,
        variant: "destructive",
      });
      return;
    }

    if (items.includes(trimmed)) {
      toast({
        title: "Error",
        description: `This ${type} already exists`,
        variant: "destructive",
      });
      return;
    }

    const validation = validateMediaUrl(trimmed);
    if (!validation.valid) {
      toast({
        title: "Invalid URL",
        description: validation.error,
        variant: "destructive",
      });
      return;
    }

    setItems([...items, trimmed]);
    setNewItem("");
    handleOpenChange(false);
  };

  return (
    <Popover onOpenChange={handleOpenChange} open={open}>
      <PopoverTrigger asChild>
        <div>{children}</div>
      </PopoverTrigger>
      <PopoverContent className="w-[460px] p-6" align={align}>
        <div className="space-y-3">
          <h3 className="comet-body-s-accented">{title}</h3>
          <div className="flex gap-2">
            <div className="relative flex-1">
              <Input
                ref={inputRef}
                placeholder={placeholder}
                value={newItem}
                onChange={(e) => setNewItem(e.target.value)}
                onClick={(e) => e.stopPropagation()}
                onKeyDown={(event) => {
                  if (event.key === "Enter") {
                    handleAddItem();
                  }
                }}
              />
            </div>
            <Button type="button" variant="default" onClick={handleAddItem}>
              Add
            </Button>
          </div>
          {promptVariables.length > 0 && (
            <p className="comet-body-xs text-light-slate">
              Available variables:{" "}
              <PromptVariablesList
                variables={promptVariables}
                onVariableClick={handleVariableClick}
              />
            </p>
          )}
        </div>
      </PopoverContent>
    </Popover>
  );
};

export default AddMediaPopover;
