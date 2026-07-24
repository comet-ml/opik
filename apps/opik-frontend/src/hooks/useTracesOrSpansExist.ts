import { UseQueryOptions } from "@tanstack/react-query";
import isBoolean from "lodash/isBoolean";

import useTracesExist from "@/api/traces/useTracesExist";
import useSpansExist from "@/api/traces/useSpansExist";
import { TRACE_DATA_TYPE } from "@/constants/traces";
import { LOGS_SOURCE } from "@/types/traces";

type UseTracesOrSpansExistParams = {
  projectId: string;
  type: TRACE_DATA_TYPE;
  source?: LOGS_SOURCE;
};

export default function useTracesOrSpansExist(
  params: UseTracesOrSpansExistParams,
  config: Omit<UseQueryOptions, "queryKey" | "queryFn">,
) {
  const isTracesData = params.type === TRACE_DATA_TYPE.traces;
  const isEnabled = isBoolean(config.enabled) ? config.enabled : true;

  const { data: tracesData } = useTracesExist(
    { projectId: params.projectId, source: params.source },
    {
      ...config,
      enabled: isTracesData && isEnabled,
    } as never,
  );

  const { data: spansData } = useSpansExist(
    { projectId: params.projectId, source: params.source },
    {
      ...config,
      enabled: !isTracesData && isEnabled,
    } as never,
  );

  const data = isTracesData ? tracesData : spansData;

  return { exists: data?.exists ?? false };
}
