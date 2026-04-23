import React from "react";
import TagListRenderer from "@/shared/TagListRenderer/TagListRenderer";
import useThreadUpdateMutation from "@/api/traces/useThreadUpdateMutation";
import { usePermissions } from "@/contexts/PermissionsContext";

type ThreadDetailsTagsProps = {
  tags: string[];
  threadId: string;
  projectId: string;
};

const ThreadDetailsTags: React.FunctionComponent<ThreadDetailsTagsProps> = ({
  tags = [],
  threadId,
  projectId,
}) => {
  const {
    permissions: { canTagTrace },
  } = usePermissions();
  const threadUpdateMutation = useThreadUpdateMutation();

  const mutateTags = (tags: string[]) => {
    threadUpdateMutation.mutate({
      projectId,
      threadId,
      data: {
        tags,
      },
    });
  };

  const handleAddTag = (newTag: string) => {
    mutateTags([...tags, newTag]);
  };

  const handleDeleteTag = (tag: string) => {
    mutateTags(tags.filter((t) => t !== tag));
  };

  return (
    <TagListRenderer
      tags={canTagTrace ? tags : []}
      immutableTags={canTagTrace ? [] : tags}
      onAddTag={handleAddTag}
      onDeleteTag={handleDeleteTag}
      canAdd={canTagTrace}
      tagVariant="gray"
    />
  );
};

export default ThreadDetailsTags;
