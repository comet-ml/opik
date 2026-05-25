import { useMemo } from "react";
import uniq from "lodash/uniq";

import useTracesOrSpansList, {
  TRACE_DATA_TYPE,
} from "@/hooks/useTracesOrSpansList";
import { COLUMN_TYPE } from "@/types/shared";
import { BaseTraceData } from "@/types/traces";
import { ChipOptionsResult } from "@/shared/filter-chips/types";

interface UseErrorTypeOptionsArgs {
  projectId: string | "";
  type?: TRACE_DATA_TYPE;
}

const ERROR_FILTER = [
  {
    id: "error_filter",
    field: "error_info",
    operator: "is_not_empty" as const,
    type: COLUMN_TYPE.errors,
    value: "",
  },
];

const SAMPLE_SIZE = 100;

export const useErrorTypeOptions = (
  args: UseErrorTypeOptionsArgs,
): ChipOptionsResult => {
  const { projectId, type = TRACE_DATA_TYPE.traces } = args;
  const hasProjectId = Boolean(projectId);

  const { data, isPending } = useTracesOrSpansList(
    {
      projectId,
      type,
      page: 1,
      size: SAMPLE_SIZE,
      truncate: true,
      stripAttachments: true,
      filters: ERROR_FILTER,
    },
    { enabled: hasProjectId },
  );

  const items = useMemo(() => {
    const traces = (data?.content || []) as BaseTraceData[];
    const exceptionTypes = traces
      .map((trace) => trace.error_info?.exception_type?.trim())
      .filter((t): t is string => Boolean(t));
    return uniq(exceptionTypes).sort();
  }, [data?.content]);

  const effectiveLoading = hasProjectId && isPending;

  return useMemo(
    () => ({ items, isLoading: effectiveLoading }),
    [items, effectiveLoading],
  );
};
