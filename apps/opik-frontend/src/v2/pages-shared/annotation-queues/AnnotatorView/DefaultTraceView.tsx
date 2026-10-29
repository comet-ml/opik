import React from "react";
import { Trace } from "@/types/traces";
import { useUnifiedMedia } from "@/hooks/useUnifiedMedia";
import { MediaProvider } from "@/shared/PrettyLLMMessage/llmMessages";
import AttachmentsList from "@/v2/pages-shared/traces/TraceDetailsPanel/TraceDataViewer/AttachmentsList";
import CodeBlock from "@/v2/pages-shared/traces/TraceDetailsPanel/TraceDataViewer/CodeBlock/CodeBlock";

type DefaultTraceViewProps = {
  trace: Trace;
};

const DefaultTraceView: React.FC<DefaultTraceViewProps> = ({ trace }) => {
  const { media, transformedInput, transformedOutput } = useUnifiedMedia(trace);

  return (
    <MediaProvider media={media}>
      <div className="flex flex-col gap-3">
        <AttachmentsList media={media} />
        <CodeBlock
          title="Input"
          data={transformedInput}
          prettifyConfig={{ fieldType: "input" }}
          preserveKey="syntax-highlighter-annotation-input"
          withSearch
        />
        <CodeBlock
          title="Output"
          data={transformedOutput}
          prettifyConfig={{ fieldType: "output" }}
          preserveKey="syntax-highlighter-annotation-output"
          withSearch
        />
        <CodeBlock
          title="Metadata"
          data={trace.metadata || {}}
          preserveKey="syntax-highlighter-annotation-metadata"
          withSearch
          defaultOpen={false}
        />
      </div>
    </MediaProvider>
  );
};

export default DefaultTraceView;
