import React, { useState } from "react";
import { Plus, Tag } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover";
import { Input } from "@/components/ui/input";
import { useToast } from "@/components/ui/use-toast";
import RemovableTag from "@/components/shared/RemovableTag/RemovableTag";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

export type TagListRendererProps = {
  tags: string[];
  onAddTag: (tag: string) => void;
  onDeleteTag: (tag: string) => void;
  align?: "start" | "end";
  size?: "md" | "sm";
};

const TagListRenderer: React.FC<TagListRendererProps> = ({
  tags = [],
  onAddTag,
  onDeleteTag,
  align = "end",
  size = "md",
}) => {
  const { toast } = useToast();
  const [open, setOpen] = useState(false);
  const [newTag, setNewTag] = useState<string>("");

  const tagSizeClass = size === "sm" ? "w-3" : "w-4";
  const tagMarginClass = size === "sm" ? "mx-0" : "mx-1";

  const handleAddTag = () => {
    if (!newTag) return;

    if (tags.includes(newTag)) {
      toast({
        title: "Error",
        description: `The tag "${newTag}" already exists`,
        variant: "destructive",
      });
      return;
    }

    onAddTag(newTag);
    setNewTag("");
    setOpen(false);
  };

  return (
    <div className="flex min-h-7 w-full flex-wrap items-center gap-1.5 overflow-x-hidden">
      <TooltipWrapper content="Tags list">
        <Tag className={`${tagMarginClass} ${tagSizeClass} text-muted-slate`} />
      </TooltipWrapper>
      {[...tags].sort().map((tag) => {
        return (
          <RemovableTag
            label={tag}
            key={tag}
            size="md"
            onDelete={() => onDeleteTag(tag)}
          />
        );
      })}
      <Popover onOpenChange={setOpen} open={open}>
        <PopoverTrigger asChild>
          <Button
            data-testid="add-tag-button"
            variant="outline"
            size="icon-2xs"
          >
            <Plus />
          </Button>
        </PopoverTrigger>
        <PopoverContent className="w-[420px] p-6" align={align}>
          <div className="flex gap-2">
            <Input
              placeholder="New tag"
              value={newTag}
              onChange={(event) => setNewTag(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === "Enter") {
                  handleAddTag();
                }
              }}
            />
            <Button variant="default" onClick={handleAddTag}>
              Add tag
            </Button>
          </div>
        </PopoverContent>
      </Popover>
    </div>
  );
};

export default TagListRenderer;
