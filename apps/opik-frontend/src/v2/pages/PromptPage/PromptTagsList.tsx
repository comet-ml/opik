import TagListRenderer from "@/shared/TagListRenderer/TagListRenderer";
import usePromptUpdateMutation from "@/api/prompts/usePromptUpdateMutation";
import { Prompt } from "@/types/prompts";
import { usePermissions } from "@/contexts/PermissionsContext";

type PromptTagsListProps = {
  tags: string[];
  prompt?: Prompt;
  promptId: string;
};

const PromptTagsList: React.FC<PromptTagsListProps> = ({
  tags = [],
  prompt,
  promptId,
}) => {
  const {
    permissions: { canEditPrompts },
  } = usePermissions();
  const { mutate } = usePromptUpdateMutation();

  const mutateTags = (newTags: string[]) => {
    mutate({
      prompt: {
        ...prompt,
        id: promptId,
        tags: newTags,
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
      tags={tags}
      onAddTag={handleAddTag}
      onDeleteTag={handleDeleteTag}
      align="start"
      size="sm"
      tagVariant="lavender"
      readOnly={!canEditPrompts}
    />
  );
};

export default PromptTagsList;
