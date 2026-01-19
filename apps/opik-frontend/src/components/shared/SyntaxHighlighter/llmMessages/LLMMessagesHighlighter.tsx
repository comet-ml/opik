import React, { ReactNode, useMemo } from "react";
import { FoldVertical, UnfoldVertical } from "lucide-react";
import {
  CodeOutput,
  PrettifyConfig,
} from "@/components/shared/SyntaxHighlighter/types";
import PrettyLLMMessage from "@/components/shared/PrettyLLMMessage";
import { Button } from "@/components/ui/button";
import {
  Tooltip,
  TooltipContent,
  TooltipTrigger,
} from "@/components/ui/tooltip";
import { Separator } from "@/components/ui/separator";
import { getProvider } from "./providers/registry";
import { LLMProvider, LLMMessageDescriptor, LLMBlockDescriptor } from "./types";
import { useLLMMessagesExpandAll } from "@/components/shared/SyntaxHighlighter/hooks/useSyntaxHighlighterHooks";

export interface LLMMessagesHighlighterProps {
  codeOutput: CodeOutput;
  data: object;
  prettifyConfig?: PrettifyConfig;
  provider: LLMProvider;
  modeSelector: ReactNode;
  copyButton: ReactNode;
  scrollRef?: React.RefObject<HTMLDivElement>;
  onScroll?: (e: React.UIEvent<HTMLDivElement>) => void;
  maxHeight?: string;
  preserveKey?: string;
}

const LLMMessagesHighlighter: React.FC<LLMMessagesHighlighterProps> = ({
  data,
  prettifyConfig,
  provider,
  modeSelector,
  copyButton,
  scrollRef,
  onScroll,
  maxHeight,
  preserveKey,
}) => {
  // Get provider implementation and map messages
  const messages = useMemo<LLMMessageDescriptor[]>(() => {
    const providerImpl = getProvider(provider);
    if (!providerImpl) return [];
    return providerImpl.mapper(data, prettifyConfig);
  }, [data, prettifyConfig, provider]);

  // Get all message IDs for expand/collapse all functionality
  const allMessageIds = useMemo(() => {
    return messages.map((msg) => msg.id);
  }, [messages]);

  // Use the hook for expand/collapse logic
  const {
    isAllExpanded,
    expandedMessages,
    handleToggleAll,
    handleValueChange,
  } = useLLMMessagesExpandAll(allMessageIds, preserveKey);

  // Render block from descriptor (no switch needed!)
  const renderBlock = (descriptor: LLMBlockDescriptor, key: string) => {
    const Component = descriptor.component as React.ComponentType<
      Record<string, unknown>
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
        <Button onClick={handleToggleAll} variant="ghost" size="icon-2xs">
          {isAllExpanded ? <FoldVertical /> : <UnfoldVertical />}
        </Button>
      </TooltipTrigger>
      <TooltipContent side="bottom">
        {isAllExpanded ? "Collapse all" : "Expand all"}
      </TooltipContent>
    </Tooltip>
  );

  return (
    <div className="overflow-hidden">
      <div className="flex h-10 items-center justify-between rounded-md border border-border bg-primary-foreground pr-2">
        <div className="flex min-w-40">{modeSelector}</div>
        <div className="flex flex-1 items-center justify-end gap-0.5">
          <>
            {messages.length > 0 && expandCollapseButton}
            <Separator orientation="vertical" className="mx-1 h-4" />
            {copyButton}
          </>
        </div>
      </div>
      <div>
        <div
          ref={scrollRef}
          onScroll={onScroll}
          className={maxHeight ? "overflow-y-auto pt-3" : "pt-3"}
          style={maxHeight ? { maxHeight } : undefined}
        >
          {messages.length > 0 ? (
            <PrettyLLMMessage.Container
              type="multiple"
              value={expandedMessages}
              onValueChange={handleValueChange}
            >
              {messages.map((message) => (
                <PrettyLLMMessage.Root key={message.id} value={message.id}>
                  <PrettyLLMMessage.Header
                    role={message.role}
                    label={message.label}
                  />
                  <PrettyLLMMessage.Content>
                    {renderContentBlocks(message)}
                    {message.footer && (
                      <PrettyLLMMessage.Footer
                        usage={message.footer.usage}
                        finishReason={message.footer.finishReason}
                      />
                    )}
                  </PrettyLLMMessage.Content>
                </PrettyLLMMessage.Root>
              ))}
            </PrettyLLMMessage.Container>
          ) : (
            <div className="text-sm text-muted-foreground">No messages</div>
          )}
        </div>
      </div>
    </div>
  );
};

export default LLMMessagesHighlighter;
