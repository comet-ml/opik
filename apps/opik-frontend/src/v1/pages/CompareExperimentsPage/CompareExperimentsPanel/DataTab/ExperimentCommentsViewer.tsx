import React from "react";
import useTraceCommentsBatchDeleteMutation from "@/api/traces/useTraceCommentsBatchDeleteMutation";
import useCreateTraceCommentMutation from "@/api/traces/useCreateTraceCommentMutation";
import useUpdateTraceCommentMutation from "@/api/traces/useUpdateTraceCommentMutation";
import { CommentItems } from "@/types/comment";
import { MessageSquareMore } from "lucide-react";
import ExpandableSection from "@/shared/ExpandableSection/ExpandableSection";
import CommentsSection from "@/shared/UserComment/CommentsSection";

export type ExperimentCommentsViewerProps = {
  comments?: CommentItems;
  traceId: string;
  sectionIdx: number;
};

const ExperimentCommentsViewer: React.FC<ExperimentCommentsViewerProps> = ({
  comments = [],
  traceId,
  sectionIdx,
}) => {
  const traceDeleteMutation = useTraceCommentsBatchDeleteMutation();
  const createTraceMutation = useCreateTraceCommentMutation();
  const updateTraceMutation = useUpdateTraceCommentMutation();

  const onSubmit = (text: string) => {
    createTraceMutation.mutate({
      text,
      traceId,
    });
  };

  const onEditSubmit = (commentId: string, text: string) => {
    updateTraceMutation.mutate({
      text,
      commentId,
      traceId,
    });
  };

  const onDelete = (commentId: string) => {
    traceDeleteMutation.mutate({
      ids: [commentId],
      traceId,
    });
  };

  return (
    <ExpandableSection
      icon={<MessageSquareMore className="size-4" />}
      title="Comments"
      queryParamName="expandedCommentSections"
      sectionIdx={sectionIdx}
      count={comments.length}
    >
      <CommentsSection
        comments={comments}
        onSubmit={onSubmit}
        onEditSubmit={onEditSubmit}
        onDelete={onDelete}
        formClassName="px-3"
        listClassName="mt-2 pb-3"
        commentClassName="px-3 py-2 hover:bg-soft-background"
      />
    </ExpandableSection>
  );
};

export default ExperimentCommentsViewer;
