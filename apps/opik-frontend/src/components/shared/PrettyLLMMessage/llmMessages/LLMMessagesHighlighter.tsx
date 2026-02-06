import React, { ReactNode, useRef, useMemo, useCallback, useState } from "react";
import { useVirtualizer } from "@tanstack/react-virtual";
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
import {
  LLMProvider,
  LLMMessageDescriptor,
  LLMBlockDescriptor,
  LLMMapperResult,
} from "./types";
import { useLLMMessagesExpandAll } from "@/components/shared/SyntaxHighlighter/hooks/useSyntaxHighlighterHooks";

const ESTIMATED_COLLAPSED_HEIGHT = 36;
const VIRTUALIZATION_THRESHOLD = 30;
const VIRTUAL_OVERSCAN = 5;

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

function renderBlock(descriptor: LLMBlockDescriptor, key: string) {
  const Component = descriptor.component as React.ComponentType<
    typeof descriptor.props
  >;
  return <Component key={key} {...descriptor.props} />;
}

function renderContentBlocks(message: LLMMessageDescriptor) {
  return message.blocks.map((block, idx) =>
    renderBlock(block, `${message.id}-block-${idx}`),
  );
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
  const mapperResult = useMemo<LLMMapperResult>(() => {
    const providerImpl = getProvider(provider);
    if (!providerImpl) return { messages: [] };
    return providerImpl.mapper(data, prettifyConfig);
  }, [data, prettifyConfig, provider]);

  const { messages, usage } = mapperResult;
  const shouldVirtualize = messages.length > VIRTUALIZATION_THRESHOLD;

  const internalScrollRef = useRef<HTMLDivElement>(null);
  const effectiveScrollRef = scrollRef ?? internalScrollRef;

  const [scrollMargin, setScrollMargin] = useState(0);

  const listRef = useCallback((node: HTMLDivElement | null) => {
    if (node) {
      setScrollMargin(node.offsetTop);
    }
  }, []);

  const virtualizer = useVirtualizer({
    count: messages.length,
    getScrollElement: () => effectiveScrollRef.current,
    estimateSize: () => ESTIMATED_COLLAPSED_HEIGHT,
    overscan: VIRTUAL_OVERSCAN,
    getItemKey: (index) => messages[index].id,
    enabled: shouldVirtualize,
    scrollMargin,
  });

  const allMessageIds = useMemo(
    () => messages.map((msg) => msg.id),
    [messages],
  );

  const {
    isAllExpanded,
    expandedMessages,
    handleToggleAll,
    handleValueChange,
  } = useLLMMessagesExpandAll(allMessageIds, preserveKey);

  const renderMessage = useCallback(
    (message: LLMMessageDescriptor) => (
      <PrettyLLMMessage.Root key={message.id} value={message.id}>
        <PrettyLLMMessage.Header role={message.role} label={message.label} />
        <PrettyLLMMessage.Content>
          {renderContentBlocks(message)}
          {message.finishReason && (
            <PrettyLLMMessage.FinishReason
              finishReason={message.finishReason}
            />
          )}
        </PrettyLLMMessage.Content>
      </PrettyLLMMessage.Root>
    ),
    [messages],
  );

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

  function renderVirtualizedMessages() {
    return (
      <PrettyLLMMessage.Container
        type="multiple"
        value={expandedMessages}
        onValueChange={handleValueChange}
      >
        <div
          ref={listRef}
          className="relative"
          style={{ height: virtualizer.getTotalSize() }}
        >
          {virtualizer.getVirtualItems().map((virtualRow) => {
            const message = messages[virtualRow.index];
            return (
              <div
                key={message.id}
                data-index={virtualRow.index}
                ref={virtualizer.measureElement}
                className="absolute left-0 w-full pb-1"
                style={{
                  transform: `translateY(${virtualRow.start - virtualizer.options.scrollMargin}px)`,
                }}
              >
                {renderMessage(message)}
              </div>
            );
          })}
        </div>
      </PrettyLLMMessage.Container>
    );
  }

  function renderStaticMessages() {
    return (
      <PrettyLLMMessage.Container
        type="multiple"
        value={expandedMessages}
        onValueChange={handleValueChange}
        className="space-y-1"
      >
        {messages.map((message) => renderMessage(message))}
      </PrettyLLMMessage.Container>
    );
  }

  return (
    <div className="overflow-hidden">
      <div className="flex h-10 items-center justify-between rounded-md border border-border bg-primary-foreground pr-2">
        <div className="flex min-w-40">{modeSelector}</div>
        <div className="flex flex-1 items-center justify-end gap-0.5">
          {messages.length > 0 && expandCollapseButton}
          <Separator orientation="vertical" className="mx-1 h-4" />
          {copyButton}
        </div>
      </div>
      <div
        ref={effectiveScrollRef as React.RefObject<HTMLDivElement>}
        onScroll={onScroll}
        className={
          maxHeight || shouldVirtualize ? "overflow-y-auto pt-3" : "pt-3"
        }
        style={maxHeight ? { maxHeight } : undefined}
      >
        {messages.length > 0 ? (
          <>
            {shouldVirtualize
              ? renderVirtualizedMessages()
              : renderStaticMessages()}
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
  );
};

export default LLMMessagesHighlighter;
