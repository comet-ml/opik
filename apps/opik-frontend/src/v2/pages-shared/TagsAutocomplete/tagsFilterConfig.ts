import React from "react";

import { FilterRowConfig } from "@/types/filters";
import TagsAutocomplete, {
  TagsAutocompleteEntityType,
} from "./TagsAutocomplete";

type GetTagsFilterConfigArgs = {
  projectId: string;
  entityType: TagsAutocompleteEntityType;
  promptId?: string;
};

export const getTagsFilterConfig = ({
  projectId,
  entityType,
  promptId,
}: GetTagsFilterConfigArgs): Record<string, FilterRowConfig> => ({
  tags: {
    keyComponent: TagsAutocomplete as React.FC<unknown> & {
      placeholder: string;
      value: string;
      onValueChange: (value: string) => void;
    },
    keyComponentProps: {
      projectId,
      entityType,
      ...(promptId && { promptId }),
    },
  },
});
