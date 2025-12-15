import React, { useMemo } from "react";
import { useQueryClient } from "@tanstack/react-query";
import uniq from "lodash/uniq";
import isObject from "lodash/isObject";
import isArray from "lodash/isArray";

import { getJSONPaths } from "@/lib/utils";
import useTracesOrSpansList, {
  TRACE_DATA_TYPE,
} from "@/hooks/useTracesOrSpansList";
import Autocomplete from "@/components/shared/Autocomplete/Autocomplete";
import { PROJECTS_SELECT_QUERY_KEY } from "@/components/pages-shared/automations/ProjectsSelectBox";
import { Project } from "@/types/projects";
import { PLAYGROUND_PROJECT_NAME } from "@/constants/shared";

type CachedProjectsData = { content: Project[]; total: number };

export type TRACE_AUTOCOMPLETE_ROOT_KEY = "input" | "output" | "metadata";

const PLAYGROUND_DEFAULT_SUGGESTIONS = [
  "output.output",
  "input.messages[0].role",
  "input.messages[0].content",
];

type TracesOrSpansPathsAutocompleteProps = {
  projectId: string | "";
  rootKeys: TRACE_AUTOCOMPLETE_ROOT_KEY[];
  hasError?: boolean;
  value: string;
  onValueChange: (value: string) => void;
  type?: TRACE_DATA_TYPE;
  placeholder?: string;
  excludeRoot?: boolean;
  projectName?: string; // Optional: if provided, avoids cache lookup
  datasetColumnNames?: string[]; // Optional: dataset column names from playground
  includeIntermediateNodes?: boolean; // Optional: when true, includes all intermediate paths (e.g., "input", "input.key1", "input.key1.key2")
};

const TracesOrSpansPathsAutocomplete: React.FC<
  TracesOrSpansPathsAutocompleteProps
> = ({
  projectId,
  rootKeys,
  hasError,
  value,
  onValueChange,
  type = TRACE_DATA_TYPE.traces,
  placeholder,
  excludeRoot = false,
  projectName: projectNameProp,
  datasetColumnNames,
  includeIntermediateNodes = false,
}) => {
  // Default placeholder based on type
  const defaultPlaceholder =
    type === TRACE_DATA_TYPE.spans
      ? "Select a key from recent span"
      : "Select a key from recent trace";
  const finalPlaceholder = placeholder ?? defaultPlaceholder;
  const isProjectId = Boolean(projectId);
  const queryClient = useQueryClient();

  const { data, isPending } = useTracesOrSpansList(
    {
      projectId,
      type,
      page: 1,
      size: 100,
      truncate: true,
    },
    {
      enabled: isProjectId,
    },
  );

  const { data: dataNonTruncated, isPending: isPendingNonTruncated } =
    useTracesOrSpansList(
      {
        projectId,
        type,
        page: 1,
        size: 10,
        truncate: false,
      },
      {
        enabled: isProjectId,
      },
    );

  // Get project name from prop if provided, otherwise look up from cached projects data
  const projectName = useMemo(() => {
    // If projectName is provided as prop, use it directly
    if (projectNameProp) return projectNameProp;

    // Otherwise, look up from cached projects data (already fetched by ProjectsSelectBox)
    if (!projectId) return null;

    const cachedQueries = queryClient.getQueryCache().findAll({
      queryKey: [PROJECTS_SELECT_QUERY_KEY],
      exact: false,
    });

    for (const query of cachedQueries) {
      const queryData = query.state.data as CachedProjectsData | undefined;
      if (queryData?.content) {
        const project = queryData.content.find((p) => p.id === projectId);
        if (project) return project.name;
      }
    }

    return null;
  }, [projectId, queryClient, projectNameProp]);

  const items = useMemo(() => {
    // Combine both truncated (100) and non-truncated (10) traces
    // Truncated traces maximize chance of catching changes in structure
    // Non-truncated traces provide fallback for complete JSON paths
    const truncatedTraces = data?.content || [];
    const nonTruncatedTraces = dataNonTruncated?.content || [];
    const allTraces = [...truncatedTraces, ...nonTruncatedTraces];
    const hasTraces = allTraces.length > 0;
    const isPlaygroundProject = projectName === PLAYGROUND_PROJECT_NAME;

    let baseSuggestions: string[] = [];

    // If it's the playground project and there are no traces, use default suggestions
    if (isPlaygroundProject && !hasTraces) {
      baseSuggestions = PLAYGROUND_DEFAULT_SUGGESTIONS;
    } else {
      // Extract paths from all traces (truncated + non-truncated)
      baseSuggestions = allTraces.reduce<string[]>((acc, d) => {
        return acc.concat(
          rootKeys.reduce<string[]>(
            (internalAcc, key) =>
              internalAcc.concat(
                isObject(d[key]) || isArray(d[key])
                  ? getJSONPaths(d[key], key, [], includeIntermediateNodes).map(
                      (path) =>
                        excludeRoot
                          ? path.substring(path.indexOf(".") + 1)
                          : path,
                    )
                  : [],
              ),
            [],
          ),
        );
      }, []);
    }

    // When includeIntermediateNodes is enabled and not excluding root, add root keys as suggestions
    const rootObjectSuggestions: string[] =
      includeIntermediateNodes && !excludeRoot ? [...rootKeys] : [];

    // Add dataset column names at the bottom if provided
    const datasetSuggestions =
      datasetColumnNames?.map(
        (columnName) => `metadata.dataset_item_data.${columnName}`,
      ) || [];

    // Combine and deduplicate suggestions
    // Root objects come first (when enabled), then regular paths, then dataset columns
    const allSuggestions = uniq([
      ...rootObjectSuggestions,
      ...baseSuggestions,
      ...datasetSuggestions,
    ]);

    // Filter and sort
    return allSuggestions
      .filter((p) =>
        value ? p.toLowerCase().includes(value.toLowerCase()) : true,
      )
      .sort();
  }, [
    data,
    dataNonTruncated,
    rootKeys,
    value,
    excludeRoot,
    projectName,
    datasetColumnNames,
    includeIntermediateNodes,
  ]);

  return (
    <Autocomplete
      value={value}
      onValueChange={onValueChange}
      items={items}
      hasError={hasError}
      isLoading={isProjectId ? isPending || isPendingNonTruncated : false}
      placeholder={finalPlaceholder}
    />
  );
};

export default TracesOrSpansPathsAutocomplete;
