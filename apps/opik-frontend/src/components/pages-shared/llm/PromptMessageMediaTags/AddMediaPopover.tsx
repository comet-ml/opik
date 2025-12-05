import React, { useMemo, useState } from "react";
import { Button } from "@/components/ui/button";
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover";
import { Input } from "@/components/ui/input";
import { useToast } from "@/components/ui/use-toast";

export type MediaType = "image" | "video" | "audio";

export interface AddMediaPopoverProps {
  type: MediaType;
  items: string[];
  setItems: (newItems: string[]) => void;
  maxItems?: number;
  align?: "start" | "end";
  onOpenChange?: (open: boolean) => void;
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
  children,
}) => {
  const { toast } = useToast();
  const [open, setOpen] = useState(false);
  const [newItem, setNewItem] = useState<string>("");

  const resolvedMaxItems = maxItems ?? DEFAULT_MAX_ITEMS[type];

  const placeholder = useMemo(() => {
    if (type === "image") {
      return "Enter image URL or template variable";
    }
    if (type === "video") {
      return "Enter video URL or template variable";
    }
    return "Enter audio URL or template variable";
  }, [type]);

  const helperText = useMemo(() => {
    if (type === "image") {
      return "You can add an image URL or template variable. ";
    }
    if (type === "video") {
      return "You can add a video URL or template variable. ";
    }
    return "You can add an audio URL or template variable. ";
  }, [type]);

  const handleOpenChange = (isOpen: boolean) => {
    setOpen(isOpen);
    onOpenChange?.(isOpen);

    if (!isOpen) {
      setNewItem("");
    }
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
          <div className="flex gap-2">
            <div className="relative flex-1">
              <Input
                placeholder={placeholder}
                value={newItem}
                onChange={(e) => setNewItem(e.target.value)}
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
          <p className="comet-body-xs text-muted-foreground">
            {helperText}
            <code>{`{{${type}}}`}</code>
          </p>
        </div>
      </PopoverContent>
    </Popover>
  );
};

export default AddMediaPopover;
