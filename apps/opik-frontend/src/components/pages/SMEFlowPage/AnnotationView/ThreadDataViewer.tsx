import React, { useMemo } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import last from "lodash/last";
import { Thread } from "@/types/traces";
import { useSMEFlow } from "../SMEFlowContext";
import useTracesList from "@/api/traces/useTracesList";
import TraceMessages from "@/components/pages-shared/traces/TraceMessages/TraceMessages";
import { COLUMN_TYPE } from "@/types/shared";

const ThreadDataViewer: React.FunctionComponent = () => {
  const { currentItem } = useSMEFlow();

  const thread = currentItem as Thread;

  const { data: tracesData } = useTracesList(
    {
      projectId: thread?.project_id || "",
      filters: [
        {
          id: "",
          field: "thread_id",
          type: COLUMN_TYPE.string,
          operator: "=",
          value: thread?.id || "",
        },
      ],
      page: 1,
      size: 1000,
      truncate: true,
    },
    {
      enabled: !!thread?.id,
      placeholderData: keepPreviousData,
    },
  );

  const traces = useMemo(
    () =>
      (tracesData?.content ?? []).sort((t1, t2) => t1.id.localeCompare(t2.id)),
    [tracesData],
  );

  return (
    <div className="pr-4">
      <TraceMessages traces={traces} traceId={last(traces)?.id} />
    </div>
  );
};

export default ThreadDataViewer;
