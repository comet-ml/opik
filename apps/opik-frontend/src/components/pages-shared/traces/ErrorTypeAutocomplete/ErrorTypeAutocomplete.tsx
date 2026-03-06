import React, { useMemo } from "react";
import uniq from "lodash/uniq";

import useTracesOrSpansList, {
  TRACE_DATA_TYPE,
} from "@/hooks/useTracesOrSpansList";
import Autocomplete from "@/components/shared/Autocomplete/Autocomplete";
import { COLUMN_TYPE } from "@/types/shared";
import { BaseTraceData } from "@/types/traces";

type ErrorTypeAutocompleteProps = {
  projectId: string | "";
  hasError?: boolean;
  value: string;
  onValueChange: (value: string) => void;
  type?: TRACE_DATA_TYPE;
};

const ERROR_FILTER = [
  {
    id: "error_filter",
    field: "error_info",
    operator: "is_not_empty" as const,
    type: COLUMN_TYPE.errors,
    value: "",
  },
];

const ErrorTypeAutocomplete: React.FC<ErrorTypeAutocompleteProps> = ({
  projectId,
  hasError,
  value,
  onValueChange,
  type = TRACE_DATA_TYPE.traces,
}) => {
  const isProjectId = Boolean(projectId);

  const { data, isPending } = useTracesOrSpansList(
    {
      projectId,
      type,
      page: 1,
      size: 100,
      truncate: true,
      filters: ERROR_FILTER,
    },
    {
      enabled: isProjectId,
    },
  );

  const items = useMemo(() => {
    const traces = (data?.content || []) as BaseTraceData[];

    const exceptionTypes = traces
      .map((trace) => trace.error_info?.exception_type?.trim())
      .filter((t): t is string => Boolean(t));

    return uniq(exceptionTypes)
      .filter((t) =>
        value ? t.toLowerCase().includes(value.toLowerCase()) : true,
      )
      .sort();
  }, [data, value]);

  return (
    <Autocomplete
      value={value}
      onValueChange={onValueChange}
      items={items}
      hasError={hasError}
      isLoading={isProjectId ? isPending : false}
      placeholder="Select error type"
    />
  );
};

export default ErrorTypeAutocomplete;
