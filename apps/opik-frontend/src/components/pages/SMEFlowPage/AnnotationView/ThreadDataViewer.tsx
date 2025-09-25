import React, { useMemo } from "react";
import { keepPreviousData } from "@tanstack/react-query";
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
      size: 100,
    },
    {
      enabled: !!thread?.id,
      placeholderData: keepPreviousData,
    },
  );

  const traces = useMemo(() => tracesData?.content ?? [], [tracesData]);

  return (
    <div className="pr-4">
      <TraceMessages traces={traces} traceId={traces[0]?.id} />
    </div>
  );
};

export default ThreadDataViewer;
