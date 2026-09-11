import React, { useMemo } from "react";

import Autocomplete from "@/shared/Autocomplete/Autocomplete";
import { filterTagsByQuery } from "./helpers";
import { TagsAutocompleteEntityType, useTagsOptions } from "./useTagsOptions";

type TagsAutocompleteProps = {
  projectId: string;
  entityType: TagsAutocompleteEntityType;
  value: string;
  onValueChange: (value: string) => void;
  hasError?: boolean;
  promptId?: string;
};

const TagsAutocomplete: React.FC<TagsAutocompleteProps> = ({
  projectId,
  entityType,
  value,
  onValueChange,
  hasError,
  promptId,
}) => {
  const { items: allTags, isLoading } = useTagsOptions({
    projectId,
    entityType,
    promptId,
  });

  const items = useMemo(
    () => filterTagsByQuery(allTags, value),
    [allTags, value],
  );

  return (
    <Autocomplete
      value={value}
      onValueChange={onValueChange}
      items={items}
      hasError={hasError}
      isLoading={isLoading}
      placeholder="Select a recent tag"
    />
  );
};

export default TagsAutocomplete;
