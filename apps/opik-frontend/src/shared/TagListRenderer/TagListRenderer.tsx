import React, { useState } from "react";
import { Plus, Tag } from "lucide-react";
import { Button, ButtonProps } from "@/ui/button";
import { Popover, PopoverContent, PopoverTrigger } from "@/ui/popover";
import { Input } from "@/ui/input";
import { useToast } from "@/ui/use-toast";
import RemovableTag from "@/shared/RemovableTag/RemovableTag";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { cn } from "@/lib/utils";
import { TagProps } from "@/ui/tag";

type TagSizeConfig = {
  addButtonSize: ButtonProps["size"];
  addButtonClassName?: string;
  rowMinHeight: string;
};

const DEFAULT_TAG_SIZE_CONFIG: TagSizeConfig = {
  addButtonSize: "icon-2xs",
  addButtonClassName: "size-5",
  rowMinHeight: "min-h-7",
};

const TAG_SIZE_CONFIG: Partial<
  Record<NonNullable<TagProps["size"]>, TagSizeConfig>
> = {
  sm: { addButtonSize: "icon-3xs", rowMinHeight: "min-h-4" },
  md: { addButtonSize: "icon-2xs", rowMinHeight: "min-h-6" },
  lg: { addButtonSize: "icon-xs", rowMinHeight: "min-h-7" },
};

export type TagListRendererProps = {
  tags: string[];
  immutableTags?: string[];
  onAddTag: (tag: string) => void;
  onDeleteTag: (tag: string) => void;
  align?: "start" | "end";
  size?: "md" | "sm";
  className?: string;
  tooltipText?: string;
  placeholderText?: string;
  addButtonText?: string;
  tagType?: string; // For error messages (e.g., "tag", "version tag")
  canAdd?: boolean;
  tagVariant?: TagProps["variant"];
  tagSize?: TagProps["size"];
};

const TagListRenderer: React.FC<TagListRendererProps> = ({
  tags = [],
  immutableTags = [],
  onAddTag,
  onDeleteTag,
  align = "end",
  size = "md",
  className,
  tooltipText = "Tags list",
  placeholderText = "New tag",
  addButtonText = "Add tag",
  tagType = "tag",
  canAdd = true,
  tagVariant,
  tagSize = "default",
}) => {
  const { toast } = useToast();
  const [open, setOpen] = useState(false);
  const [newTag, setNewTag] = useState<string>("");

  const hasTags = tags.length > 0 || immutableTags.length > 0;

  const tagSizeConfig =
    (tagSize && TAG_SIZE_CONFIG[tagSize]) ?? DEFAULT_TAG_SIZE_CONFIG;

  const tagMarginClass = size === "sm" ? "mx-0" : "mx-1";

  const isImmutableTag = (tag: string): boolean =>
    immutableTags.some((t) => t.toLowerCase() === tag.toLowerCase());

  const handleAddTag = () => {
    if (!newTag) return;

    if (tags.includes(newTag) || isImmutableTag(newTag)) {
      toast({
        title: "Error",
        description: `The ${tagType} "${newTag}" already exists`,
        variant: "destructive",
      });
      return;
    }

    onAddTag(newTag);
    setNewTag("");
    setOpen(false);
  };

  if (!canAdd && !hasTags) {
    return null;
  }

  return (
    <div
      className={cn(
        "flex w-full flex-wrap items-center gap-2 overflow-x-hidden",
        tagSizeConfig.rowMinHeight,
        className,
      )}
    >
      <TooltipWrapper content={tooltipText}>
        <Tag className={`${tagMarginClass} size-3 text-muted-slate`} />
      </TooltipWrapper>
      {[...immutableTags].sort().map((tag) => (
        <RemovableTag
          label={tag}
          key={`immutable-${tag}`}
          size={tagSize}
          variant={tagVariant}
        />
      ))}
      {[...tags].sort().map((tag) => (
        <RemovableTag
          label={tag}
          key={tag}
          size={tagSize}
          variant={tagVariant}
          onDelete={() => onDeleteTag(tag)}
        />
      ))}
      {canAdd && (
        <Popover onOpenChange={setOpen} open={open}>
          <PopoverTrigger asChild>
            <Button
              data-testid="add-tag-button"
              variant="outline"
              size={tagSizeConfig.addButtonSize}
              className={cn(tagSizeConfig.addButtonClassName)}
            >
              <Plus />
            </Button>
          </PopoverTrigger>
          <PopoverContent className="w-[420px] p-6" align={align}>
            <div className="flex gap-2">
              <Input
                placeholder={placeholderText}
                value={newTag}
                onChange={(event) => setNewTag(event.target.value)}
                onKeyDown={(event) => {
                  if (event.key === "Enter") {
                    handleAddTag();
                  }
                }}
              />
              <Button variant="default" onClick={handleAddTag}>
                {addButtonText}
              </Button>
            </div>
          </PopoverContent>
        </Popover>
      )}
    </div>
  );
};

export default TagListRenderer;
