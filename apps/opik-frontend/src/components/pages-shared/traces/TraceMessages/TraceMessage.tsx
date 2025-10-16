import React, { useMemo } from "react";

import { Trace, USER_FEEDBACK_SCORE } from "@/types/traces";
import { MessageRenderer } from "@/components/shared/MessageRenderer";
import LikeFeedback from "@/components/pages-shared/traces/TraceMessages/LikeFeedback";
import { Separator } from "@/components/ui/separator";
import { Button } from "@/components/ui/button";
import { USER_FEEDBACK_NAME } from "@/constants/shared";
import { cn } from "@/lib/utils";
import isFunction from "lodash/isFunction";

type TraceMessageProps = {
  trace: Trace;
  handleOpenTrace?: (id: string) => void;
};

const TraceMessage: React.FC<TraceMessageProps> = ({
  trace,
  handleOpenTrace,
}) => {
  const withActions = isFunction(handleOpenTrace);

  const userFeedback = useMemo(() => {
    return (trace.feedback_scores ?? []).find(
      (f) => f.name === USER_FEEDBACK_NAME,
    )?.value as USER_FEEDBACK_SCORE;
  }, [trace.feedback_scores]);

  return (
    <div
      className={cn("pt-4 first:pt-0", withActions && "border-b")}
      data-trace-message-id={trace.id}
    >
      <div key={`${trace.id}_input`} className="mb-4 flex justify-end">
        <div className="relative min-w-[20%] max-w-[90%] rounded-t-xl rounded-bl-xl bg-[var(--message-input-background)] px-4 py-2">
          <MessageRenderer message={trace.input} attemptTextExtraction={true} />
        </div>
      </div>
      <div key={`${trace.id}_output`} className="flex justify-start">
        <div className="relative min-w-[20%] max-w-[90%] rounded-t-xl rounded-br-xl bg-primary-foreground px-4 py-2 dark:bg-secondary">
          <MessageRenderer
            message={trace.output}
            attemptTextExtraction={true}
          />
        </div>
      </div>
      {withActions && (
        <div className="mb-2 mt-1 flex items-center gap-0.5 p-0.5">
          <LikeFeedback state={userFeedback} traceId={trace.id} />
          <Separator orientation="vertical" className="mx-1 h-3" />
          <Button
            variant="ghost"
            size="2xs"
            onClick={() => handleOpenTrace(trace.id)}
          >
            View trace
          </Button>
        </div>
      )}
    </div>
  );
};

export default TraceMessage;
