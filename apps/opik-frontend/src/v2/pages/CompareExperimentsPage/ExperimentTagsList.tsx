import TagListRenderer from "@/shared/TagListRenderer/TagListRenderer";
import useExperimentUpdateMutation from "@/api/datasets/useExperimentUpdate";
import { Experiment } from "@/types/datasets";
import { cn } from "@/lib/utils";
import { usePermissions } from "@/contexts/PermissionsContext";

type ExperimentTagsListProps = {
  tags: string[];
  experiment: Experiment;
  experimentId: string;
  className?: string;
};

const ExperimentTagsList: React.FC<ExperimentTagsListProps> = ({
  tags = [],
  experiment,
  experimentId,
  className,
}) => {
  const { mutate } = useExperimentUpdateMutation();
  const {
    permissions: { canCreateExperiments },
  } = usePermissions();

  const mutateTags = (newTags: string[]) => {
    mutate({
      experiment: {
        ...experiment,
        id: experimentId,
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
      readOnly={!canCreateExperiments}
      align="start"
      className={cn("[&>:first-child]:-mr-1", className)}
      tagVariant="purple"
    />
  );
};

export default ExperimentTagsList;
