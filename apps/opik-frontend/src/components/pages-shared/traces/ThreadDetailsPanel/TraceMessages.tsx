import React, { useCallback } from "react";
import { Trace } from "@/types/traces";
import TraceMessage from "@/components/pages-shared/traces/ThreadDetailsPanel/TraceMessage";
import isNumber from "lodash/isNumber";

type TraceMessagesProps = {
  traces: Trace[];
  traceId?: string;
  handleOpenTrace: (id: string) => void;
};

const TraceMessages: React.FC<TraceMessagesProps> = ({
  traces,
  traceId,
  handleOpenTrace,
}) => {
  const setRef = useCallback(
    (element: HTMLDivElement | null) => {
      const top = (
        element?.querySelector(
          `[data-trace-message-id="${traceId}"]`,
        ) as HTMLDivElement
      )?.offsetTop;

      if (isNumber(top)) {
        element?.scrollTo({
          top,
          behavior: "smooth",
        });
      }
    },
    [traceId],
  );

  return (
    <div
      className="relative flex size-full justify-center overflow-y-auto"
      ref={setRef}
    >
      <div className="flex max-w-full flex-col gap-2">
        {traces.map((t) => (
          <TraceMessage
            key={t.id}
            trace={t}
            handleOpenTrace={handleOpenTrace}
          />
        ))}
      </div>
    </div>
  );
};

export default TraceMessages;
