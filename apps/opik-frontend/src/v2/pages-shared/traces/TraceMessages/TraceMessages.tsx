import React, { useCallback } from "react";
import { Trace } from "@/types/traces";
import TraceMessage, {
  RenderMessageContent,
} from "@/v2/pages-shared/traces/TraceMessages/TraceMessage";
import isNumber from "lodash/isNumber";

type TraceMessagesProps = {
  traces: Trace[];
  traceId?: string;
  handleOpenTrace: (id: string, filterToolCalls: boolean) => void;
  renderContent?: RenderMessageContent;
};

const TraceMessages: React.FC<TraceMessagesProps> = ({
  traces,
  traceId,
  handleOpenTrace,
  renderContent,
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
      className="relative flex size-full justify-center overflow-y-auto px-4"
      ref={setRef}
    >
      <div className="flex w-full flex-col gap-2">
        {traces.map((t) => (
          <TraceMessage
            key={t.id}
            trace={t}
            handleOpenTrace={handleOpenTrace}
            renderContent={renderContent}
          />
        ))}
      </div>
    </div>
  );
};

export default TraceMessages;
