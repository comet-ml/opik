import React, { useMemo } from "react";
import JsonView from "react18-json-view";
import isObject from "lodash/isObject";
import isUndefined from "lodash/isUndefined";

import { Trace, USER_FEEDBACK_SCORE } from "@/types/traces";
import MarkdownPreview from "@/components/shared/MarkdownPreview/MarkdownPreview";
import LikeFeedback from "@/components/pages-shared/traces/ThreadDetailsPanel/LikeFeedback";
import { Separator } from "@/components/ui/separator";
import { Button } from "@/components/ui/button";
import { USER_FEEDBACK_NAME } from "@/constants/shared";
import { prettifyMessage } from "@/lib/traces";
import { toString } from "@/lib/utils";

type TraceMessageProps = {
  trace: Trace;
  handleOpenTrace: (id: string) => void;
};

const TraceMessage: React.FC<TraceMessageProps> = ({
  trace,
  handleOpenTrace,
}) => {
  const userFeedback = useMemo(() => {
    return (trace.feedback_scores ?? []).find(
      (f) => f.name === USER_FEEDBACK_NAME,
    )?.value as USER_FEEDBACK_SCORE;
  }, [trace.feedback_scores]);

  const input = useMemo(() => {
    const message = prettifyMessage(trace.input).message;

    if (isObject(message)) {
      return (
        <JsonView
          src={message}
          theme="github"
          className="comet-code"
          dark={true}
          collapseStringsAfterLength={10000}
          enableClipboard={false}
        />
      );
    } else if (isUndefined(message)) {
      return <span className="text-white">-</span>;
    } else {
      return (
        <MarkdownPreview className="text-white">
          {toString(message)}
        </MarkdownPreview>
      );
    }
  }, [trace.input]);

  const output = useMemo(() => {
    const message = prettifyMessage(trace.output, { type: "output" }).message;

    if (isObject(message)) {
      return (
        <JsonView
          src={message}
          className="comet-code"
          theme="github"
          collapseStringsAfterLength={10000}
          enableClipboard={false}
        />
      );
    } else if (isUndefined(message)) {
      return "-";
    } else {
      return <MarkdownPreview>{toString(message)}</MarkdownPreview>;
    }
  }, [trace.output]);

  return (
    <div className="border-b pt-4" data-trace-message-id={trace.id}>
      <div key={`${trace.id}_input`} className="mb-4 flex justify-end">
        <div className="relative min-w-[20%] max-w-[90%] rounded-t-xl rounded-bl-xl bg-[#7678EF] px-4 py-2">
          {input}
        </div>
      </div>
      <div key={`${trace.id}_output`} className="flex justify-start">
        <div className="relative min-w-[20%] max-w-[90%] rounded-t-xl rounded-br-xl bg-primary-foreground px-4 py-2">
          {output}
        </div>
      </div>
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
    </div>
  );
};

export default TraceMessage;
