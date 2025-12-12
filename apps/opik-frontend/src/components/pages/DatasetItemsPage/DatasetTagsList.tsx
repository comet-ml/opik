import TagListRenderer from "@/components/shared/TagListRenderer/TagListRenderer";
import useDatasetUpdateMutation from "@/api/datasets/useDatasetUpdateMutation";
import { Dataset } from "@/types/datasets";

type DatasetTagsListProps = {
  tags: string[];
  dataset?: Dataset;
  datasetId: string;
  className?: string;
};

const DatasetTagsList: React.FC<DatasetTagsListProps> = ({
  tags = [],
  dataset,
  datasetId,
  className,
}) => {
  const { mutate } = useDatasetUpdateMutation();

  const mutateTags = (newTags: string[]) => {
    mutate({
      dataset: {
        ...dataset,
        id: datasetId,
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

export default DatasetTagsList;
