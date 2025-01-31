import React, { useState } from "react";
import { Plus, Tag } from "lucide-react";
import RemovableTag from "./RemovableTag";
import useTraceUpdateMutation from "@/api/traces/useTraceUpdateMutation";
import useSpanUpdateMutation from "@/api/traces/useSpanUpdateMutation";
import { Button } from "@/components/ui/button";
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover";
import { Input } from "@/components/ui/input";
import useAppStore from "@/store/AppStore";
import { Span, Trace } from "@/types/traces";
import { useToast } from "@/components/ui/use-toast";

type TagListProps = {
  tags: string[];
  data: Trace | Span;
  projectId: string;
  traceId: string;
  spanId?: string;
};

const TagList: React.FunctionComponent<TagListProps> = ({
  tags = [],
  data,
  projectId,
  traceId,
  spanId,
}) => {
  const { toast } = useToast();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const [open, setOpen] = useState(false);
  const traceUpdateMutation = useTraceUpdateMutation();
  const spanUpdateMutation = useSpanUpdateMutation();

  const [newTag, setNewTag] = useState<string>("");

  const mutateTags = (newTags: string[]) => {
    if (spanId) {
      const parentId = (data as Span).parent_span_id;

      spanUpdateMutation.mutate({
        projectId,
        spanId,
        span: {
          workspace_name: workspaceName,
          project_id: projectId,
          ...(parentId && { parent_span_id: parentId }),
          trace_id: traceId,
          tags: newTags,
        },
      });
    } else {
      traceUpdateMutation.mutate({
        projectId,
        traceId,
        trace: {
          workspace_name: workspaceName,
          project_id: projectId,
          tags: newTags,
        },
      });
    }
  };

  const handleAddTag = () => {
    if (!newTag) return;

    if (tags.includes(newTag)) {
      toast({
        title: "Error",
        description: `The tag "${newTag}" already exist`,
        variant: "destructive",
      });
      return;
    }

    mutateTags([...tags, newTag]);
    setNewTag("");
    setOpen(false);
  };

  const handleDeleteTag = (tag: string) => {
    mutateTags(tags.filter((t) => t !== tag));
  };

  return (
    <div className="flex min-h-7 w-full flex-wrap items-center gap-1 overflow-x-hidden">
      <Tag className="mx-1 size-4 text-muted-slate" />
      {tags.sort().map((tag) => {
        return (
          <RemovableTag
            label={tag}
            key={tag}
            size="lg"
            onDelete={handleDeleteTag}
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
            <Plus className="size-4" />
          </Button>
        </PopoverTrigger>
        <PopoverContent className="w-[420px] p-6" align="end">
          <div className="flex gap-2">
            <Input
              placeholder="New tag"
              value={newTag}
              onChange={(event) => setNewTag(event.target.value)}
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

export default TagList;
