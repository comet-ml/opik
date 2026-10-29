import React from "react";
import last from "lodash/last";
import noop from "lodash/noop";
import { Trace } from "@/types/traces";
import TraceMessages from "@/v2/pages-shared/traces/TraceMessages/TraceMessages";

type DefaultThreadViewProps = {
  traces: Trace[];
  onOpenTrace?: (id: string, filterToolCalls: boolean) => void;
};

const DefaultThreadView: React.FC<DefaultThreadViewProps> = ({
  traces,
  onOpenTrace = noop,
}) => (
  <TraceMessages
    traces={traces}
    traceId={last(traces)?.id}
    handleOpenTrace={onOpenTrace}
  />
);

export default DefaultThreadView;
