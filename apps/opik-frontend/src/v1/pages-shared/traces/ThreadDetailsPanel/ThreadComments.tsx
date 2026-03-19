import React from "react";
import {
  DetailsActionSectionValue,
  DetailsActionSectionLayout,
} from "@/v1/pages-shared/traces/DetailsActionSection";
import { CommentItem } from "@/types/comment";
import useCreateThreadCommentMutation from "@/api/traces/useCreateThreadCommentMutation";
import useThreadCommentsBatchDeleteMutation from "@/api/traces/useThreadCommentsBatchDeleteMutation";
import useUpdateThreadCommentMutation from "@/api/traces/useUpdateThreadCommentMutation";
import CommentsSection from "@/v1/pages-shared/traces/UserComment/CommentsSection";

export type ThreadCommentsProps = {
  activeSection?: DetailsActionSectionValue | null;
  setActiveSection: (v: DetailsActionSectionValue | null) => void;
  comments: CommentItem[];
  threadId: string;
  projectId: string;
};

const ThreadComments: React.FC<ThreadCommentsProps> = ({
  activeSection,
  setActiveSection,
  comments,
  threadId,
  projectId,
}) => {
  const threadCommentsBatchDeleteMutation =
    useThreadCommentsBatchDeleteMutation();
  const createThreadCommentMutation = useCreateThreadCommentMutation();
  const updateThreadCommentMutation = useUpdateThreadCommentMutation();

  const onSubmit = (text: string) => {
    createThreadCommentMutation.mutate({
      text,
      threadId,
      projectId,
    });
  };

  const onEditSubmit = (commentId: string, text: string) => {
    updateThreadCommentMutation.mutate({
      text,
      commentId,
      projectId,
    });
  };

  const onDelete = (commentId: string) => {
    threadCommentsBatchDeleteMutation.mutate({
      ids: [commentId],
      projectId,
      threadId,
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
        comments={comments}
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

export default ThreadComments;
