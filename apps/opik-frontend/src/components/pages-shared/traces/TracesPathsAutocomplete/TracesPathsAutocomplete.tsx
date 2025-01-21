import React, { useMemo } from "react";
import uniq from "lodash/uniq";
import isObject from "lodash/isObject";
import isArray from "lodash/isArray";

import { getJSONPaths } from "@/lib/utils";
import useTracesOrSpansList, {
  TRACE_DATA_TYPE,
} from "@/hooks/useTracesOrSpansList";
import Autocomplete from "@/components/shared/Autocomplete/Autocomplete";

type ROOT_KEY = "input" | "output" | "metadata";

type TracesPathsAutocompleteProps = {
  projectId: string;
  rootKeys: ROOT_KEY[];
  value: string;
  onValueChange: (value: string) => void;
};

const TracesPathsAutocomplete: React.FC<TracesPathsAutocompleteProps> = ({
  projectId,
  rootKeys,
  value,
  onValueChange,
}) => {
  const { data, isPending } = useTracesOrSpansList(
    {
      projectId,
      type: TRACE_DATA_TYPE.traces,
      page: 1,
      size: 100,
      truncate: true,
    },
    {},
  );

  const items = useMemo(() => {
    return uniq(
      (data?.content || []).reduce<string[]>((acc, d) => {
        return acc.concat(
          rootKeys.reduce<string[]>(
            (internalAcc, key) =>
              internalAcc.concat(
                isObject(d[key]) || isArray(d[key])
                  ? getJSONPaths(d[key], key)
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
  }, [data, rootKeys, value]);

  return (
    <Autocomplete
      value={value}
      onValueChange={onValueChange}
      items={items}
      isLoading={isPending}
      placeholder="Select a key from recent trace"
    />
  );
};

export default TracesPathsAutocomplete;
