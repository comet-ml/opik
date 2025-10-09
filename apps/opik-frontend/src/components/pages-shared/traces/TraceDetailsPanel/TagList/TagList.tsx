import useTraceUpdateMutation from "@/api/traces/useTraceUpdateMutation";
import useSpanUpdateMutation from "@/api/traces/useSpanUpdateMutation";
import useAppStore from "@/store/AppStore";
import { Span, Trace } from "@/types/traces";
import TagListRenderer from "@/components/shared/TagListRenderer/TagListRenderer";

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
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const traceUpdateMutation = useTraceUpdateMutation();
  const spanUpdateMutation = useSpanUpdateMutation();

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

  const handleAddTag = (newTag: string) => {
    mutateTags([...tags, newTag]);
  };

  const handleDeleteTag = (tag: string) => {
    mutateTags(tags.filter((t) => t !== tag));
  };

  return (
    <TagListRenderer
      tags={tags}
      onAddTag={handleAddTag}
      onDeleteTag={handleDeleteTag}
      size="sm"
    />
  );
};

export default TagList;
