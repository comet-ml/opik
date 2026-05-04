import React from "react";
import { Span, Trace } from "@/types/traces";
import { useUnifiedMedia } from "@/hooks/useUnifiedMedia";
import { MediaProvider } from "@/shared/PrettyLLMMessage/llmMessages";
import CollapsibleSection from "@/v2/pages-shared/traces/TraceDetailsPanel/CollapsibleSection";
import CodeBlock from "./CodeBlock";
import AttachmentsList from "./AttachmentsList";
import EventsList from "./EventsList";
import Loader from "@/shared/Loader/Loader";

type DetailsTabProps = {
  data: Trace | Span;
  isLoading: boolean;
  search?: string;
};

const DetailsTab: React.FunctionComponent<DetailsTabProps> = ({
  data,
  isLoading,
  search,
}) => {
  const { media, transformedInput, transformedOutput } = useUnifiedMedia(data);

  const hasMetadata = Boolean(data.metadata);
  const hasTokenUsage = Boolean(data.usage);

  return (
    <MediaProvider media={media}>
      <div className="flex flex-col gap-2">
        <AttachmentsList media={media} />
        {isLoading ? (
          <CollapsibleSection title="Input" disabled bodyClassName="p-2">
            <Loader />
          </CollapsibleSection>
        ) : (
          <CodeBlock
            title="Input"
            data={transformedInput}
            prettifyConfig={{ fieldType: "input" }}
            preserveKey="syntax-highlighter-trace-sidebar-input"
            search={search}
            withSearch
          />
        )}
        {isLoading ? (
          <CollapsibleSection title="Output" disabled bodyClassName="p-2">
            <Loader />
          </CollapsibleSection>
        ) : (
          <CodeBlock
            title="Output"
            data={transformedOutput}
            prettifyConfig={{ fieldType: "output" }}
            preserveKey="syntax-highlighter-trace-sidebar-output"
            search={search}
            withSearch
          />
        )}
        <EventsList data={data} isLoading={isLoading} search={search} />
        {hasMetadata && (
          <CodeBlock
            title="Metadata"
            withSearch
            data={data.metadata}
            search={search}
          />
        )}
        {hasTokenUsage && (
          <CodeBlock
            title="Token usage"
            data={data.usage as object}
            withSearch
            search={search}
          />
        )}
      </div>
    </MediaProvider>
  );
};

export default DetailsTab;
