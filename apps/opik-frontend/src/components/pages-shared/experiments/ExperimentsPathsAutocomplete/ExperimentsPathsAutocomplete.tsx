import React, { useMemo } from "react";
import uniq from "lodash/uniq";
import isObject from "lodash/isObject";
import isArray from "lodash/isArray";

import { Sorting } from "@/types/sorting";
import { getJSONPaths } from "@/lib/utils";
import Autocomplete from "@/components/shared/Autocomplete/Autocomplete";
import useGroupedExperimentsList, { GroupedExperiment } from "@/hooks/useGroupedExperimentsList";
import useAppStore from "@/store/AppStore";
import { Filters } from "@/types/filters";
import { COLUMN_TYPE } from "@/types/shared";

type ExperimentsPathsAutocompleteProps = {
  hasError?: boolean;
  value: string;
  onValueChange: (value: string) => void;
  promptId?: string;
  datasetId?: string;
  sorting?: Sorting;
  placeholder?: string;
  excludeRoot?: boolean;
};

const ExperimentsPathsAutocomplete: React.FC<
  ExperimentsPathsAutocompleteProps
> = ({
  hasError,
  value,
  onValueChange,
  promptId,
  datasetId,
  sorting,
  placeholder = "Select a key from recent experiments",
  excludeRoot = false,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  // Convert datasetId to filters format if provided
  const filters: Filters = useMemo(() => {
    if (datasetId) {
      return [{
        id: "dataset_filter",
        field: "dataset_id",
        type: COLUMN_TYPE.string,
        operator: "=",
        value: datasetId
      }];
    }
    return [];
  }, [datasetId]);

  const { data, isPending } = useGroupedExperimentsList({
    workspaceName,
    promptId,
    filters,
    sorting,
    search: "", // Empty search to get all experiments
    page: 1,
    size: 100,
  });

  const items = useMemo(() => {
    const key = "metadata";

    const allPaths = (data?.experiments || []).reduce((acc: string[], d: GroupedExperiment) => {
      return acc.concat(
        isObject(d[key]) || isArray(d[key])
          ? getJSONPaths(d[key], key).map((path: string) =>
              excludeRoot ? path.substring(path.indexOf(".") + 1) : path,
            )
          : [],
      );
    }, [] as string[]);

    return (uniq(allPaths) as string[])
      .filter((p: string) =>
        value ? p.toLowerCase().includes(value.toLowerCase()) : true,
      )
      .sort();
  }, [data, value, excludeRoot]);

  return (
    <Autocomplete
      value={value}
      onValueChange={onValueChange}
      items={items}
      hasError={hasError}
      isLoading={isPending}
      placeholder={placeholder}
    />
  );
};

export default ExperimentsPathsAutocomplete;
