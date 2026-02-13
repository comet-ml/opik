import React, {
  useState,
  useMemo,
  useCallback,
  useEffect,
  useRef,
} from "react";
import { Tag as TagIcon, Plus, Check, X } from "lucide-react";

import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogFooter,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { useToast } from "@/components/ui/use-toast";
import RemovableTag from "@/components/shared/RemovableTag/RemovableTag";
import { Tag } from "@/components/ui/tag";
import { generateTagVariant } from "@/lib/traces";

const MAX_TAG_LENGTH = 100;
const MAX_ENTITIES = 1000;
const MAX_TAGS = 50;

export type EntityWithTags = {
  id: string;
  tags?: string[];
};

type ManageTagsDialogProps<T extends EntityWithTags = EntityWithTags> = {
  entities: T[];
  open: boolean | number;
  setOpen: (open: boolean | number) => void;
  onUpdate: (tagsToAdd: string[], tagsToRemove: string[]) => Promise<void>;
  isAllItemsSelected?: boolean;
  totalCount?: number;
};

const ManageTagsDialog: React.FunctionComponent<ManageTagsDialogProps> = ({
  entities,
  open,
  setOpen,
  onUpdate,
  isAllItemsSelected = false,
  totalCount,
}) => {
  const { toast } = useToast();
  const [newTags, setNewTags] = useState<Set<string>>(new Set());
  const [tagsToRemove, setTagsToRemove] = useState<Set<string>>(new Set());
  const [isAdding, setIsAdding] = useState(false);
  const [newTagInput, setNewTagInput] = useState("");
  const inputRef = useRef<HTMLInputElement>(null);

  const isOverLimit = !isAllItemsSelected && entities.length > MAX_ENTITIES;

  useEffect(() => {
    if (isOverLimit && open) {
      toast({
        title: "Error",
        description: `You can only add tags to up to ${MAX_ENTITIES} items at a time. Please select fewer items.`,
        variant: "destructive",
      });
      setOpen(false);
    }
  }, [isOverLimit, open, setOpen, toast]);

  useEffect(() => {
    if (isAdding && inputRef.current) {
      inputRef.current.focus();
    }
  }, [isAdding]);

  const tagCounts = useMemo(() => {
    const counts = new Map<string, number>();
    entities.forEach((entity) => {
      (entity?.tags || []).forEach((tag) => {
        counts.set(tag, (counts.get(tag) || 0) + 1);
      });
    });
    return counts;
  }, [entities]);

  const commonTags = useMemo(() => {
    if (entities.length === 0) return [];
    return Array.from(tagCounts.keys()).filter(
      (tag) => tagCounts.get(tag) === entities.length,
    );
  }, [entities.length, tagCounts]);

  const handleClose = () => {
    setOpen(false);
    setNewTagInput("");
    setNewTags(new Set());
    setTagsToRemove(new Set());
    setIsAdding(false);
  };

  const handleAddNewTag = useCallback(() => {
    const trimmedTag = newTagInput.trim();

    if (!trimmedTag) return;

    if (newTags.has(trimmedTag)) {
      toast({
        title: "Tag already added",
        description: `Tag "${trimmedTag}" is already in the list`,
        variant: "destructive",
      });
      return;
    }

    if (
      tagCounts.has(trimmedTag) &&
      tagCounts.get(trimmedTag) === entities.length
    ) {
      toast({
        title: "Tag already exists",
        description: `Tag "${trimmedTag}" is already applied to all selected items`,
        variant: "destructive",
      });
      return;
    }

    setNewTags((prev) => new Set(prev).add(trimmedTag));
    setNewTagInput("");
    setIsAdding(false);
  }, [newTagInput, newTags, toast, tagCounts, entities]);

  const handleRemoveNewTag = useCallback((tag: string) => {
    setNewTags((prev) => {
      const next = new Set(prev);
      next.delete(tag);
      return next;
    });
  }, []);

  const handleRemoveExistingTag = useCallback((tag: string) => {
    setTagsToRemove((prev) => new Set(prev).add(tag));
  }, []);

  const handleRestoreTag = useCallback((tag: string) => {
    setTagsToRemove((prev) => {
      const next = new Set(prev);
      next.delete(tag);
      return next;
    });
  }, []);

  const handleUpdateTags = async () => {
    const allTagsAfterUpdate = entities.map((entity) => {
      const currentTags = new Set(entity.tags || []);
      tagsToRemove.forEach((tag) => currentTags.delete(tag));
      newTags.forEach((tag) => currentTags.add(tag));
      return currentTags.size;
    });

    const maxTagCount = Math.max(...allTagsAfterUpdate);
    if (maxTagCount > MAX_TAGS) {
      toast({
        title: "Tag limit exceeded",
        description: `An item can only have up to ${MAX_TAGS} tags`,
        variant: "destructive",
      });
      return;
    }

    try {
      await onUpdate(Array.from(newTags), Array.from(tagsToRemove));

      const removedMsg =
        tagsToRemove.size > 0 ? `${tagsToRemove.size} removed` : "";
      const addedMsg = newTags.size > 0 ? `${newTags.size} added` : "";
      const separator = removedMsg && addedMsg ? ", " : "";

      toast({
        title: "Success",
        description: `Tags updated: ${removedMsg}${separator}${addedMsg}`,
      });
      handleClose();
    } catch {
      // Error handling is done by the mutation hook
    }
  };

  if (isOverLimit) {
    return null;
  }

  const itemCount =
    isAllItemsSelected && totalCount ? totalCount : entities.length;

  return (
    <Dialog open={Boolean(open)} onOpenChange={handleClose}>
      <DialogContent
        className="outline-none sm:max-w-[600px]"
        onEscapeKeyDown={(e) => {
          if (isAdding) e.preventDefault();
        }}
        onKeyDown={(e) => {
          if (e.key === "Enter" && !isAdding) {
            e.preventDefault();
            handleUpdateTags();
          }
        }}
      >
        <DialogHeader>
          <DialogTitle>Manage shared tags</DialogTitle>
          <p className="mt-2 text-sm text-muted-foreground">
            Add or remove tags shared by all selected items. Changes apply to
            every item.
          </p>
        </DialogHeader>

        <div className="flex max-h-80 items-start gap-2 overflow-y-auto pb-4 pt-2">
          <TagIcon className="mt-1 size-4 shrink-0 text-muted-foreground" />
          <div className="flex flex-wrap items-center gap-2">
            {commonTags.map((tag) =>
              tagsToRemove.has(tag) ? (
                <Tag
                  key={tag}
                  size="md"
                  variant={generateTagVariant(tag)}
                  className="group/removed cursor-pointer line-through opacity-40 outline outline-1 outline-current"
                  onClick={() => handleRestoreTag(tag)}
                >
                  {tag}
                </Tag>
              ) : (
                <RemovableTag
                  key={tag}
                  label={tag}
                  size="md"
                  onDelete={handleRemoveExistingTag}
                />
              ),
            )}

            {Array.from(newTags).map((tag) => (
              <RemovableTag
                key={tag}
                label={tag}
                size="md"
                className="outline outline-1 outline-current"
                onDelete={handleRemoveNewTag}
              />
            ))}
            {isAdding ? (
              <span className="inline-flex h-6 items-center rounded-md focus-within:ring-1 focus-within:ring-ring">
                <input
                  ref={inputRef}
                  type="text"
                  value={newTagInput}
                  onChange={(e) => setNewTagInput(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === "Enter") {
                      e.preventDefault();
                      handleAddNewTag();
                    }
                    if (e.key === "Escape") {
                      setNewTagInput("");
                      setIsAdding(false);
                    }
                  }}
                  onBlur={() => {
                    setNewTagInput("");
                    setIsAdding(false);
                  }}
                  maxLength={MAX_TAG_LENGTH}
                  className="comet-body-s-accented h-full w-16 rounded-l-md bg-background px-1.5 outline-none"
                />
                <Check
                  className="ml-1 size-4 shrink-0 cursor-pointer text-emerald-600"
                  onMouseDown={(e) => {
                    e.preventDefault();
                    handleAddNewTag();
                  }}
                />
                <X
                  className="ml-1 mr-1.5 size-4 shrink-0 cursor-pointer text-red-500"
                  onMouseDown={(e) => {
                    e.preventDefault();
                    setNewTagInput("");
                    setIsAdding(false);
                  }}
                />
              </span>
            ) : (
              <Tag
                size="md"
                className="cursor-pointer border-none text-muted-slate hover:text-foreground"
                onClick={() => setIsAdding(true)}
                role="button"
                data-testid="add-tag-button"
              >
                <Plus className="mr-0.5 inline-block size-3.5" />
                Add tag
              </Tag>
            )}
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={handleClose}>
            Cancel
          </Button>
          <Button
            onClick={handleUpdateTags}
            disabled={newTags.size === 0 && tagsToRemove.size === 0}
          >
            Update tags for {itemCount} {itemCount === 1 ? "item" : "items"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default ManageTagsDialog;
