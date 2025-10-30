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
import { PLAYGROUND_PROJECT_NAME } from "@/api/playground/createLogPlaygroundProcessor";

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
  placeholder = "Select a key from recent trace",
  excludeRoot = false,
  projectName: projectNameProp,
}) => {
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
    const hasTraces = data?.content && data.content.length > 0;
    const isPlaygroundProject = projectName === PLAYGROUND_PROJECT_NAME;

    // If it's the playground project and there are no traces, use default suggestions
    if (isPlaygroundProject && !hasTraces) {
      return PLAYGROUND_DEFAULT_SUGGESTIONS.filter((p) =>
        value ? p.toLowerCase().includes(value.toLowerCase()) : true,
      ).sort();
    }

    // Otherwise, use the existing logic to extract paths from traces
    return uniq(
      (data?.content || []).reduce<string[]>((acc, d) => {
        return acc.concat(
          rootKeys.reduce<string[]>(
            (internalAcc, key) =>
              internalAcc.concat(
                isObject(d[key]) || isArray(d[key])
                  ? getJSONPaths(d[key], key).map((path) =>
                      excludeRoot
                        ? path.substring(path.indexOf(".") + 1)
                        : path,
                    )
                  : [],
              ),
            [],
          ),
        );
      }, []),
    )
      .filter((p) =>
        value ? p.toLowerCase().includes(value.toLowerCase()) : true,
      )
      .sort();
  }, [data, rootKeys, value, excludeRoot, projectName]);

  return (
    <Autocomplete
      value={value}
      onValueChange={onValueChange}
      items={items}
      hasError={hasError}
      isLoading={isProjectId ? isPending : false}
      placeholder={placeholder}
    />
  );
};

export default TracesOrSpansPathsAutocomplete;
