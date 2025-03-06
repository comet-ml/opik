import React, { useMemo } from "react";
import uniq from "lodash/uniq";
import isObject from "lodash/isObject";
import isArray from "lodash/isArray";

import { getJSONPaths } from "@/lib/utils";
import useTracesOrSpansList, {
  TRACE_DATA_TYPE,
} from "@/hooks/useTracesOrSpansList";
import Autocomplete from "@/components/shared/Autocomplete/Autocomplete";

export type TRACE_AUTOCOMPLETE_ROOT_KEY = "input" | "output" | "metadata";

type TracesOrSpansPathsAutocompleteProps = {
  projectId: string | "";
  rootKeys: TRACE_AUTOCOMPLETE_ROOT_KEY[];
  hasError?: boolean;
  value: string;
  onValueChange: (value: string) => void;
  type?: TRACE_DATA_TYPE;
  placeholder?: string;
  excludeRoot?: boolean;
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
}) => {
  const isProjectId = Boolean(projectId);
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

  const items = useMemo(() => {
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
  }, [data, rootKeys, value, excludeRoot]);

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
