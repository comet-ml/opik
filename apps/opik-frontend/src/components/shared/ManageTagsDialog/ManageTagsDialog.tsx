import React, { useState, useMemo, useCallback } from "react";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogFooter,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { useToast } from "@/components/ui/use-toast";
import RemovableTag from "@/components/shared/RemovableTag/RemovableTag";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/components/ui/accordion";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { Tag } from "lucide-react";

export type EntityWithTags = {
  id: string;
  tags?: string[];
};

type ManageTagsDialogProps<T extends EntityWithTags = EntityWithTags> = {
  entities: T[];
  open: boolean | number;
  setOpen: (open: boolean | number) => void;
  onUpdate: (tagsToAdd: string[], tagsToRemove: string[]) => Promise<void>;
  maxTagLength?: number;
  maxEntities?: number;
  isAllItemsSelected?: boolean;
  totalCount?: number;
  maxTags?: number;
};

const ManageTagsDialog: React.FunctionComponent<ManageTagsDialogProps> = ({
  entities,
  open,
  setOpen,
  onUpdate,
  maxTagLength = 100,
  maxEntities = 1000,
  isAllItemsSelected = false,
  totalCount,
  maxTags,
}) => {
  const { toast } = useToast();
  const [newTagInput, setNewTagInput] = useState<string>("");
  const [newTags, setNewTags] = useState<string[]>([]);
  const [tagsToRemove, setTagsToRemove] = useState<string[]>([]);

  const isOverLimit =
    maxEntities && !isAllItemsSelected && entities.length > maxEntities;

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

    return Array.from(tagCounts.keys())
      .filter((tag) => tagCounts.get(tag) === entities.length)
      .filter((tag) => !tagsToRemove.includes(tag));
  }, [entities.length, tagCounts, tagsToRemove]);

  const individualTags = useMemo(() => {
    if (entities.length === 0) return [];

    return Array.from(tagCounts.keys())
      .filter((tag) => tagCounts.get(tag)! < entities.length)
      .filter((tag) => !tagsToRemove.includes(tag));
  }, [entities.length, tagCounts, tagsToRemove]);

  const handleClose = () => {
    setOpen(false);
    setNewTagInput("");
    setNewTags([]);
    setTagsToRemove([]);
  };

  const handleAddNewTag = useCallback(() => {
    const trimmedTag = newTagInput.trim();

    if (maxTags && newTags.length >= maxTags) {
      toast({
        title: "Too many tags",
        description: `You can only add up to ${maxTags} tags at once`,
        variant: "destructive",
      });
      return;
    }

    if (newTags.includes(trimmedTag)) {
      toast({
        title: "Error",
        description: `Tag "${trimmedTag}" is already in the list`,
        variant: "destructive",
      });
      return;
    }

    setNewTags((prev) => [...prev, trimmedTag]);
    setNewTagInput("");
  }, [newTagInput, newTags, toast, maxTags]);

  const handleRemoveNewTag = useCallback((tag: string) => {
    setNewTags((prev) => prev.filter((t) => t !== tag));
  }, []);

  const handleRemoveExistingTag = useCallback(
    (tag: string) => {
      if (maxTags && tagsToRemove.length >= maxTags) {
        toast({
          variant: "destructive",
          title: "Too many tags to remove",
          description: `You can only remove up to ${maxTags} tags at once`,
        });
        return;
      }
      setTagsToRemove((prev) => [...prev, tag]);
    },
    [maxTags, tagsToRemove, toast],
  );

  const handleUpdateTags = async () => {
    try {
      await onUpdate(newTags, tagsToRemove);

      const removedMsg =
        tagsToRemove.length > 0 ? `${tagsToRemove.length} removed` : "";
      const addedMsg = newTags.length > 0 ? `${newTags.length} added` : "";
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
    if (open) {
      toast({
        title: "Error",
        description: `You can only add tags to up to ${maxEntities} items at a time. Please select fewer items.`,
        variant: "destructive",
      });
      setOpen(false);
    }
    return null;
  }

  const hasCommonTags = commonTags.length > 0;
  const hasIndividualTags = individualTags.length > 0;
  const hasExistingTags = hasCommonTags || hasIndividualTags;
  const itemCount =
    isAllItemsSelected && totalCount ? totalCount : entities.length;

  return (
    <Dialog open={Boolean(open)} onOpenChange={handleClose}>
      <DialogContent className="sm:max-w-[600px]">
        <DialogHeader>
          <DialogTitle>Manage tags</DialogTitle>
          <p className="mt-2 text-sm text-muted-foreground">
            Add or remove tags for the selected items. Tags help you organize
            and filter your project.
          </p>
        </DialogHeader>

        <div className="space-y-6">
          <div className="space-y-3">
            <div className="space-y-1">
              <h3 className="comet-body-accented">Add new tags</h3>
              <p className="comet-body-s text-muted-foreground">
                Manage tags applied to the selected items.
              </p>
            </div>

            <div className="flex items-center gap-2">
              <Input
                placeholder="Type a tag and press Enter..."
                value={newTagInput}
                onChange={(event) => setNewTagInput(event.target.value)}
                onKeyDown={(event) => {
                  if (event.key === "Enter" && newTagInput.trim()) {
                    event.preventDefault();
                    handleAddNewTag();
                  }
                }}
                maxLength={maxTagLength}
                className="flex-1"
              />
              <Button
                variant="default"
                size="icon"
                onClick={handleAddNewTag}
                disabled={!newTagInput.trim()}
              >
                <span className="text-lg">+</span>
              </Button>
            </div>

            {newTags.length > 0 && (
              <div className="flex flex-wrap gap-2">
                {newTags.map((tag) => (
                  <RemovableTag
                    key={tag}
                    label={tag}
                    size="md"
                    onDelete={handleRemoveNewTag}
                  />
                ))}
              </div>
            )}

            {newTags.length === 0 && (
              <div className="flex items-center justify-center py-4 text-sm text-muted-foreground">
                <div className="flex flex-col items-center gap-1">
                  <Tag className="size-4" />
                  <span>No tags added yet</span>
                </div>
              </div>
            )}
          </div>
          {hasExistingTags && (
            <Accordion type="single" collapsible className="w-full">
              <AccordionItem value="edit-tags" className="border-none">
                <AccordionTrigger className="h-auto p-0 hover:no-underline">
                  <h3 className="comet-body-accented">Edit current tags</h3>
                </AccordionTrigger>
                <AccordionContent className="space-y-4 pt-3">
                  {hasCommonTags && (
                    <div className="space-y-2">
                      <div className="space-y-1">
                        <h4 className="comet-body-s-accented">Common tags</h4>
                        <p className="comet-body-xs text-muted-foreground">
                          Shared by all selected items. Removing a tag will
                          remove it from every selected item.
                        </p>
                      </div>
                      <div className="flex flex-wrap gap-2">
                        {commonTags.map((tag) => (
                          <TooltipWrapper
                            key={tag}
                            content="Remove this tag from all selected items"
                          >
                            <div>
                              <RemovableTag
                                label={tag}
                                size="md"
                                onDelete={handleRemoveExistingTag}
                              />
                            </div>
                          </TooltipWrapper>
                        ))}
                      </div>
                    </div>
                  )}
                  {hasIndividualTags && (
                    <div className="space-y-2">
                      <div className="space-y-1">
                        <h4 className="comet-body-s-accented">
                          Individual tags
                        </h4>
                        <p className="comet-body-xs text-muted-foreground">
                          Applied to some selected items. Removing a tag will
                          remove it wherever it appears in the selected items.
                        </p>
                      </div>
                      <div className="flex flex-wrap gap-2">
                        {individualTags.map((tag) => (
                          <TooltipWrapper
                            key={tag}
                            content="Remove this tag from all selected items"
                          >
                            <div>
                              <RemovableTag
                                label={tag}
                                size="md"
                                onDelete={handleRemoveExistingTag}
                              />
                            </div>
                          </TooltipWrapper>
                        ))}
                      </div>
                    </div>
                  )}
                </AccordionContent>
              </AccordionItem>
            </Accordion>
          )}
        </div>

        <DialogFooter className="mt-6">
          <Button variant="outline" onClick={handleClose}>
            Cancel
          </Button>
          <Button
            onClick={handleUpdateTags}
            disabled={newTags.length === 0 && tagsToRemove.length === 0}
          >
            Update tags for {itemCount} {itemCount === 1 ? "item" : "items"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default ManageTagsDialog;
