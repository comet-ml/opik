import { useMemo } from "react";
import uniq from "lodash/uniq";
import isObject from "lodash/isObject";
import isArray from "lodash/isArray";

import { getJSONPaths } from "@/lib/utils";
import useTracesOrSpansList, {
  TRACE_DATA_TYPE,
} from "@/hooks/useTracesOrSpansList";
import { ChipOptionsResult } from "@/shared/filter-chips/types";

export type TRACE_AUTOCOMPLETE_ROOT_KEY = "input" | "output" | "metadata";

interface UsePathsOptionsArgs {
  projectId: string;
  type: TRACE_DATA_TYPE;
  rootKeys: TRACE_AUTOCOMPLETE_ROOT_KEY[];
  excludeRoot?: boolean;
  includeIntermediateNodes?: boolean;
  datasetColumnNames?: string[];
}

export const usePathsOptions = (
  args: UsePathsOptionsArgs,
): ChipOptionsResult => {
  const {
    projectId,
    type,
    rootKeys,
    excludeRoot = false,
    includeIntermediateNodes = false,
    datasetColumnNames,
  } = args;
  const hasProjectId = Boolean(projectId);

  const { data, isPending } = useTracesOrSpansList(
    {
      projectId,
      type,
      page: 1,
      size: 100,
      truncate: true,
      stripAttachments: true,
    },
    { enabled: hasProjectId },
  );

  const { data: dataNonTruncated, isPending: isPendingNonTruncated } =
    useTracesOrSpansList(
      {
        projectId,
        type,
        page: 1,
        size: 10,
        truncate: false,
        stripAttachments: true,
      },
      { enabled: hasProjectId },
    );

  const items = useMemo(() => {
    const truncated = data?.content || [];
    const nonTruncated = dataNonTruncated?.content || [];
    const all = [...truncated, ...nonTruncated];
    const baseSuggestions = all.reduce<string[]>((acc, d) => {
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

    const rootObjectSuggestions: string[] =
      includeIntermediateNodes && !excludeRoot ? [...rootKeys] : [];

    const datasetSuggestions =
      datasetColumnNames?.map(
        (columnName) => `metadata.dataset_item_data.${columnName}`,
      ) || [];

    return uniq([
      ...rootObjectSuggestions,
      ...baseSuggestions,
      ...datasetSuggestions,
    ]).sort();
  }, [
    data?.content,
    dataNonTruncated?.content,
    rootKeys,
    excludeRoot,
    includeIntermediateNodes,
    datasetColumnNames,
  ]);

  const effectiveLoading = hasProjectId && (isPending || isPendingNonTruncated);

  return useMemo(
    () => ({ items, isLoading: effectiveLoading }),
    [items, effectiveLoading],
  );
};
