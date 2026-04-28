import React, { useMemo } from "react";

import useTracesOrSpansList, {
  TRACE_DATA_TYPE,
} from "@/hooks/useTracesOrSpansList";
import useThreadList from "@/api/traces/useThreadsList";
import useExperimentsList from "@/api/datasets/useExperimentsList";
import Autocomplete from "@/shared/Autocomplete/Autocomplete";
import { COLUMN_TYPE } from "@/types/shared";
import { extractTagsFromItems, filterTagsByQuery } from "./helpers";

export type TagsAutocompleteEntityType =
  | "traces"
  | "spans"
  | "threads"
  | "experiments";

type TagsAutocompleteProps = {
  projectId: string;
  entityType: TagsAutocompleteEntityType;
  value: string;
  onValueChange: (value: string) => void;
  hasError?: boolean;
  promptId?: string;
};

const TAGS_NOT_EMPTY_FILTER = [
  {
    id: "tags_autocomplete_not_empty",
    field: "tags",
    operator: "is_not_empty" as const,
    type: COLUMN_TYPE.list,
    value: "",
  },
];

const SAMPLE_SIZE = 100;

const TagsAutocomplete: React.FC<TagsAutocompleteProps> = ({
  projectId,
  entityType,
  value,
  onValueChange,
  hasError,
  promptId,
}) => {
  const hasProjectId = Boolean(projectId);
  const isExperiments = entityType === "experiments";
  // Experiments can be workspace-scoped (dashboard widgets), so projectId is optional.
  const queryEnabled = isExperiments || hasProjectId;

  const { data: tracesData, isPending: tracesPending } = useTracesOrSpansList(
    {
      projectId,
      type: entityType as TRACE_DATA_TYPE,
      page: 1,
      size: SAMPLE_SIZE,
      truncate: true,
      filters: TAGS_NOT_EMPTY_FILTER,
    },
    {
      enabled:
        hasProjectId && (entityType === "traces" || entityType === "spans"),
    },
  );

  const { data: threadsData, isPending: threadsPending } = useThreadList(
    {
      projectId,
      page: 1,
      size: SAMPLE_SIZE,
      truncate: true,
      filters: TAGS_NOT_EMPTY_FILTER,
    },
    {
      enabled: hasProjectId && entityType === "threads",
    },
  );

  const { data: experimentsData, isPending: experimentsPending } =
    useExperimentsList(
      {
        ...(projectId && { projectId }),
        ...(promptId && { promptId }),
        page: 1,
        size: SAMPLE_SIZE,
        filters: TAGS_NOT_EMPTY_FILTER,
      },
      {
        enabled: isExperiments,
      },
    );

  const activeData =
    entityType === "threads"
      ? threadsData
      : isExperiments
        ? experimentsData
        : tracesData;

  const isPending =
    entityType === "threads"
      ? threadsPending
      : isExperiments
        ? experimentsPending
        : tracesPending;

  const allTags = useMemo(
    () => extractTagsFromItems(activeData?.content),
    [activeData],
  );

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
      isLoading={queryEnabled && isPending}
      placeholder="Select a recent tag"
    />
  );
};

export default TagsAutocomplete;
