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

export type TagListRendererProps = {
  tags: string[];
  onAddTag: (tag: string) => void;
  onDeleteTag: (tag: string) => void;
};

const TagListRenderer: React.FC<TagListRendererProps> = ({
  tags = [],
  onAddTag,
  onDeleteTag,
}) => {
  const { toast } = useToast();
  const [open, setOpen] = useState(false);
  const [newTag, setNewTag] = useState<string>("");

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
    <div className="flex min-h-7 w-full flex-wrap items-center gap-1 overflow-x-hidden">
      <Tag className="mx-1 size-4 text-muted-slate" />
      {[...tags].sort().map((tag) => {
        return (
          <RemovableTag
            label={tag}
            key={tag}
            size="lg"
            onDelete={() => onDeleteTag(tag)}
          />
        );
      })}
      <Popover onOpenChange={setOpen} open={open}>
        <PopoverTrigger asChild>
          <Button
            data-testid="add-tag-button"
            variant="outline"
            size="icon-sm"
            className="size-7"
          >
            <Plus />
          </Button>
        </PopoverTrigger>
        <PopoverContent className="w-[420px] p-6" align="end">
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
