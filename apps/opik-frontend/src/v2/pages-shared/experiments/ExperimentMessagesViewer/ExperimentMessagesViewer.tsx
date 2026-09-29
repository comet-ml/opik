import React, { useCallback, useMemo } from "react";
import { FoldVertical, UnfoldVertical } from "lucide-react";

import PrettyLLMMessage from "@/shared/PrettyLLMMessage";
import {
  LLMBlockDescriptor,
  LLMMessageDescriptor,
  mapAndCombineMessages,
} from "@/shared/PrettyLLMMessage/llmMessages";
import { useLLMMessagesExpandAll } from "@/shared/SyntaxHighlighter/hooks/useSyntaxHighlighterHooks";
import { Button } from "@/ui/button";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/ui/tooltip";

type ExperimentMessagesViewerProps = {
  input?: unknown;
  output?: unknown;
  actions?: React.ReactNode;
};

function renderBlock(descriptor: LLMBlockDescriptor, key: string) {
  const Component = descriptor.component as React.ComponentType<
    typeof descriptor.props
  >;
  return <Component key={key} {...descriptor.props} />;
}

const ExperimentMessagesViewer: React.FunctionComponent<
  ExperimentMessagesViewerProps
> = ({ input, output, actions }) => {
  const { messages, usage } = useMemo(
    () => mapAndCombineMessages(input, output),
    [input, output],
  );

  const allMessageIds = useMemo(() => messages.map((m) => m.id), [messages]);

  // Not persisted: a stored choice would carry one row's collapsed state into
  // every other row, hiding the content of short conversations. Callers key
  // this viewer by row so the default is recomputed for each conversation.
  const {
    isAllExpanded,
    expandedMessages,
    handleToggleAll,
    handleValueChange,
  } = useLLMMessagesExpandAll(allMessageIds);

  const renderMessage = useCallback(
    (message: LLMMessageDescriptor) => (
      <PrettyLLMMessage.Root key={message.id} value={message.id}>
        <PrettyLLMMessage.Header role={message.role} label={message.label} />
        <PrettyLLMMessage.Content>
          {message.blocks.map((block, idx) =>
            renderBlock(block, `${message.id}-block-${idx}`),
          )}
          {message.finishReason && (
            <PrettyLLMMessage.FinishReason
              finishReason={message.finishReason}
            />
          )}
        </PrettyLLMMessage.Content>
      </PrettyLLMMessage.Root>
    ),
    [],
  );

  return (
    <div className="flex flex-col">
      <div className="flex justify-end gap-1 pb-1">
        {actions}
        <Tooltip>
          <TooltipTrigger asChild>
            <Button onClick={handleToggleAll} variant="outline" size="icon-2xs">
              {isAllExpanded ? <FoldVertical /> : <UnfoldVertical />}
            </Button>
          </TooltipTrigger>
          <TooltipContent side="bottom">
            {isAllExpanded ? "Collapse all" : "Expand all"}
          </TooltipContent>
        </Tooltip>
      </div>

      <PrettyLLMMessage.Container
        type="multiple"
        value={expandedMessages}
        onValueChange={handleValueChange}
        className="space-y-1"
      >
        {messages.map((message) => renderMessage(message))}
      </PrettyLLMMessage.Container>

      {usage && (
        <div className="mt-3">
          <PrettyLLMMessage.Usage usage={usage} />
        </div>
      )}
    </div>
  );
};

export default ExperimentMessagesViewer;
