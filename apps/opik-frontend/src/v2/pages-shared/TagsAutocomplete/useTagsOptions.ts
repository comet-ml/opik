import { useMemo } from "react";

import useTracesOrSpansList, {
  TRACE_DATA_TYPE,
} from "@/hooks/useTracesOrSpansList";
import useThreadList from "@/api/traces/useThreadsList";
import useExperimentsList from "@/api/datasets/useExperimentsList";
import { COLUMN_TYPE } from "@/types/shared";
import { LOGS_SOURCE } from "@/types/traces";
import { ChipOptionsResult } from "@/shared/filter-chips/types";
import { extractTagsFromItems } from "./helpers";

export type TagsAutocompleteEntityType =
  | "traces"
  | "spans"
  | "threads"
  | "experiments";

interface UseTagsOptionsArgs {
  projectId: string;
  entityType: TagsAutocompleteEntityType;
  promptId?: string;
  logsSource?: LOGS_SOURCE;
}

const TAGS_NOT_EMPTY_FILTER = [
  {
    id: "tags_options_not_empty",
    field: "tags",
    operator: "is_not_empty" as const,
    type: COLUMN_TYPE.list,
    value: "",
  },
];

const SAMPLE_SIZE = 100;

export const useTagsOptions = (args: UseTagsOptionsArgs): ChipOptionsResult => {
  const { projectId, entityType, promptId, logsSource } = args;
  const hasProjectId = Boolean(projectId);
  const isExperiments = entityType === "experiments";

  const { data: tracesData, isPending: tracesPending } = useTracesOrSpansList(
    {
      projectId,
      type: entityType as TRACE_DATA_TYPE,
      page: 1,
      size: SAMPLE_SIZE,
      truncate: true,
      stripAttachments: true,
      filters: TAGS_NOT_EMPTY_FILTER,
      logsSource,
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

  const isLoading =
    entityType === "threads"
      ? threadsPending
      : isExperiments
        ? experimentsPending
        : tracesPending;

  const items = useMemo(
    () => extractTagsFromItems(activeData?.content),
    [activeData?.content],
  );

  const queryEnabled = isExperiments || hasProjectId;
  const effectiveLoading = queryEnabled && isLoading;

  return useMemo(
    () => ({ items, isLoading: effectiveLoading }),
    [items, effectiveLoading],
  );
};
