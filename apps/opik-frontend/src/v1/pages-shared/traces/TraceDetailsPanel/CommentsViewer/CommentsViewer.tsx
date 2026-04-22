import React from "react";
import { Span, Trace } from "@/types/traces";
import useTraceCommentsBatchDeleteMutation from "@/api/traces/useTraceCommentsBatchDeleteMutation";
import useSpanCommentsBatchDeleteMutation from "@/api/traces/useSpanCommentsBatchDeleteMutation";
import useCreateSpanCommentMutation from "@/api/traces/useCreateSpanCommentMutation";
import useCreateTraceCommentMutation from "@/api/traces/useCreateTraceCommentMutation";
import useUpdateSpanCommentMutation from "@/api/traces/useUpdateSpanCommentMutation";
import useUpdateTraceCommentMutation from "@/api/traces/useUpdateTraceCommentMutation";
import {
  DetailsActionSectionValue,
  DetailsActionSectionLayout,
} from "@/v1/pages-shared/traces/DetailsActionSection";
import CommentsSection from "@/shared/UserComment/CommentsSection";

export type CommentsViewerProps = {
  data: Trace | Span;
  spanId?: string;
  traceId: string;
  projectId: string;
  activeSection?: DetailsActionSectionValue | null;
  setActiveSection: (v: DetailsActionSectionValue | null) => void;
};

const CommentsViewer: React.FC<CommentsViewerProps> = ({
  data,
  spanId,
  traceId,
  projectId,
  activeSection,
  setActiveSection,
}) => {
  const traceDeleteMutation = useTraceCommentsBatchDeleteMutation();
  const spanDeleteMutation = useSpanCommentsBatchDeleteMutation();

  const createSpanMutation = useCreateSpanCommentMutation();
  const createTraceMutation = useCreateTraceCommentMutation();

  const updateSpanMutation = useUpdateSpanCommentMutation();
  const updateTraceMutation = useUpdateTraceCommentMutation();

  const onSubmit = (text: string) => {
    if (!spanId) {
      createTraceMutation.mutate({
        text,
        traceId,
      });
      return;
    }

    createSpanMutation.mutate({
      text,
      spanId,
      projectId,
    });
  };

  const onEditSubmit = (commentId: string, text: string) => {
    if (!spanId) {
      updateTraceMutation.mutate({
        text,
        commentId,
        traceId,
      });
      return;
    }

    updateSpanMutation.mutate({
      text,
      commentId,
      projectId,
    });
  };

  const onDelete = (commentId: string) => {
    if (!spanId) {
      traceDeleteMutation.mutate({
        ids: [commentId],
        traceId,
      });
      return;
    }

    spanDeleteMutation.mutate({
      ids: [commentId],
      projectId,
    });
  };

  return (
    <DetailsActionSectionLayout
      title="Comments"
      closeTooltipContent="Close comments"
      setActiveSection={setActiveSection}
      activeSection={activeSection}
    >
      <CommentsSection
        comments={data.comments ?? []}
        onSubmit={onSubmit}
        onEditSubmit={onEditSubmit}
        onDelete={onDelete}
        formClassName="mt-4 px-6"
        listClassName="mt-3 h-full overflow-auto pb-3"
        commentClassName="px-6 hover:bg-soft-background"
      />
    </DetailsActionSectionLayout>
  );
};

export default CommentsViewer;
