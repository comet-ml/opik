import TagListRenderer from "@/components/shared/TagListRenderer/TagListRenderer";
import useExperimentUpdateMutation from "@/api/datasets/useExperimentUpdate";
import { Experiment } from "@/types/datasets";

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
      align="start"
      className={className}
    />
  );
};

export default ExperimentTagsList;
