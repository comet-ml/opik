import React, { useMemo } from "react";
import { FoldVertical, UnfoldVertical } from "lucide-react";
import { UnifiedMediaItem } from "@/hooks/useUnifiedMedia";
import {
  MediaProvider,
  detectLLMMessages,
  LLMMessageDescriptor,
  LLMBlockDescriptor,
} from "@/components/shared/PrettyLLMMessage/llmMessages";
import { getProvider } from "@/components/shared/PrettyLLMMessage/llmMessages/providers/registry";
import { Button } from "@/components/ui/button";
import {
  Tooltip,
  TooltipContent,
  TooltipTrigger,
} from "@/components/ui/tooltip";
import CopyButton from "@/components/shared/CopyButton/CopyButton";
import PrettyLLMMessage from "@/components/shared/PrettyLLMMessage";
import { useLLMMessagesExpandAll } from "@/components/shared/SyntaxHighlighter/hooks/useSyntaxHighlighterHooks";
import Loader from "@/components/shared/Loader/Loader";

type MessagesTabProps = {
  transformedInput: object;
  transformedOutput: object;
  media: UnifiedMediaItem[];
  isLoading: boolean;
};

const MessagesTab: React.FunctionComponent<MessagesTabProps> = ({
  transformedInput,
  transformedOutput,
  media,
  isLoading,
}) => {
  // Detect LLM format and get providers for input and output
  const inputDetection = useMemo(
    () => detectLLMMessages(transformedInput, { fieldType: "input" }),
    [transformedInput],
  );

  const outputDetection = useMemo(
    () => detectLLMMessages(transformedOutput, { fieldType: "output" }),
    [transformedOutput],
  );

  // Cache the output mapper result to avoid duplicate calls
  const outputMappedResult = useMemo(() => {
    if (outputDetection.supported && outputDetection.provider) {
      const provider = getProvider(outputDetection.provider);
      if (provider) {
        return provider.mapper(transformedOutput, { fieldType: "output" });
      }
    }
    return null;
  }, [outputDetection, transformedOutput]);

  // Map messages from both input and output
  const combinedMessages = useMemo(() => {
    const messages: LLMMessageDescriptor[] = [];

    // Map input messages
    if (inputDetection.supported && inputDetection.provider) {
      const provider = getProvider(inputDetection.provider);
      if (provider) {
        const inputResult = provider.mapper(transformedInput, {
          fieldType: "input",
        });
        messages.push(...inputResult.messages);
      }
    }

    // Map output messages using cached result
    if (outputMappedResult) {
      messages.push(...outputMappedResult.messages);
    }

    return messages;
  }, [inputDetection, transformedInput, outputMappedResult]);

  // Get usage info from cached output result
  const usage = useMemo(() => {
    return outputMappedResult?.usage;
  }, [outputMappedResult]);

  // Get all message IDs for expand/collapse all functionality
  const allMessageIds = useMemo(() => {
    return combinedMessages.map((msg) => msg.id);
  }, [combinedMessages]);

  // Use the hook for expand/collapse logic
  const {
    isAllExpanded,
    expandedMessages,
    handleToggleAll,
    handleValueChange,
  } = useLLMMessagesExpandAll(allMessageIds, "messages-tab-combined");

  // Generate copy text from all messages
  const copyText = useMemo(() => {
    return JSON.stringify(
      {
        input: transformedInput,
        output: transformedOutput,
      },
      null,
      2,
    );
  }, [transformedInput, transformedOutput]);

  // Render block from descriptor
  const renderBlock = (descriptor: LLMBlockDescriptor, key: string) => {
    const Component = descriptor.component as React.ComponentType<
      typeof descriptor.props
    >;
    return <Component key={key} {...descriptor.props} />;
  };

  // Render content blocks for a message
  const renderContentBlocks = (message: LLMMessageDescriptor) => {
    return message.blocks.map((block, idx) =>
      renderBlock(block, `${message.id}-block-${idx}`),
    );
  };

  // Expand/collapse button for header
  const expandCollapseButton = (
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
  );

  const copyButton = (
    <CopyButton
      message="Successfully copied messages"
      variant="outline"
      size="icon-2xs"
      text={copyText}
      tooltipText="Copy messages"
    />
  );

  if (isLoading) {
    return <Loader />;
  }

  return (
    <MediaProvider media={media}>
      <div className="overflow-hidden">
        <div className="flex h-8 items-center justify-between">
          <div className="comet-body-s-accented flex min-w-40 items-center pr-3 text-foreground">
            LLM messages
          </div>
          <div className="flex flex-1 items-center justify-end gap-2">
            {combinedMessages.length > 0 && expandCollapseButton}
            {copyButton}
          </div>
        </div>
        <div className="pt-3">
          {combinedMessages.length > 0 ? (
            <>
              <PrettyLLMMessage.Container
                type="multiple"
                value={expandedMessages}
                onValueChange={handleValueChange}
                className="space-y-1"
              >
                {combinedMessages.map((message) => (
                  <PrettyLLMMessage.Root key={message.id} value={message.id}>
                    <PrettyLLMMessage.Header
                      role={message.role}
                      label={message.label}
                    />
                    <PrettyLLMMessage.Content>
                      {renderContentBlocks(message)}
                      {message.finishReason && (
                        <PrettyLLMMessage.FinishReason
                          finishReason={message.finishReason}
                        />
                      )}
                    </PrettyLLMMessage.Content>
                  </PrettyLLMMessage.Root>
                ))}
              </PrettyLLMMessage.Container>
              {usage && (
                <div className="mt-3">
                  <PrettyLLMMessage.Usage usage={usage} />
                </div>
              )}
            </>
          ) : (
            <div className="text-sm text-muted-foreground">No messages</div>
          )}
        </div>
      </div>
    </MediaProvider>
  );
};

export default MessagesTab;
