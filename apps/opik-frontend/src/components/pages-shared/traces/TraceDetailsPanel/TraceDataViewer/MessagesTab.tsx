import React, {
  useRef,
  useMemo,
  useCallback,
  useState,
  useEffect,
} from "react";
import { useVirtualizer } from "@tanstack/react-virtual";
import { FoldVertical, UnfoldVertical } from "lucide-react";
import { UnifiedMediaItem } from "@/hooks/useUnifiedMedia";
import {
  MediaProvider,
  mapAndCombineMessages,
  LLMMessageDescriptor,
  LLMBlockDescriptor,
} from "@/components/shared/PrettyLLMMessage/llmMessages";
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

const ESTIMATED_COLLAPSED_HEIGHT = 36; // single header row height in px
const ESTIMATED_EXPANDED_HEIGHT = 200; // fallback for expanded items before measurement
const VIRTUALIZATION_THRESHOLD = 30; // render all messages below this count
const VIRTUAL_OVERSCAN = 10; // extra items rendered outside viewport
const TOGGLE_SUPPRESS_MS = 300; // ignore scroll adjustments after expand/collapse

type MessagesTabProps = {
  transformedInput: object;
  transformedOutput: object;
  media: UnifiedMediaItem[];
  isLoading: boolean;
  scrollContainerRef?: React.RefObject<HTMLDivElement>;
};

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

const MessagesTab: React.FunctionComponent<MessagesTabProps> = ({
  transformedInput,
  transformedOutput,
  media,
  isLoading,
  scrollContainerRef,
}) => {
  const { messages: combinedMessages, usage } = useMemo(
    () => mapAndCombineMessages(transformedInput, transformedOutput),
    [transformedInput, transformedOutput],
  );

  const allMessageIds = useMemo(
    () => combinedMessages.map((msg) => msg.id),
    [combinedMessages],
  );

  const {
    isAllExpanded,
    expandedMessages,
    handleToggleAll,
    handleValueChange,
  } = useLLMMessagesExpandAll(allMessageIds, "messages-tab-combined");

  const expandedSet = useMemo(
    () => new Set(expandedMessages),
    [expandedMessages],
  );

  const copyText = useMemo(
    () =>
      JSON.stringify(
        { input: transformedInput, output: transformedOutput },
        null,
        2,
      ),
    [transformedInput, transformedOutput],
  );

  const shouldVirtualize = combinedMessages.length > VIRTUALIZATION_THRESHOLD;

  const internalScrollRef = useRef<HTMLDivElement>(null);
  const effectiveScrollRef = scrollContainerRef ?? internalScrollRef;

  const [scrollMargin, setScrollMargin] = useState(0);

  const listRef = useCallback((node: HTMLDivElement | null) => {
    if (node) {
      setScrollMargin(node.offsetTop);
    }
  }, []);

  const virtualizer = useVirtualizer({
    count: combinedMessages.length,
    getScrollElement: () => effectiveScrollRef.current,
    estimateSize: (index) => {
      const id = combinedMessages[index]?.id;
      return id && expandedSet.has(id)
        ? ESTIMATED_EXPANDED_HEIGHT
        : ESTIMATED_COLLAPSED_HEIGHT;
    },
    overscan: VIRTUAL_OVERSCAN,
    getItemKey: (index) => combinedMessages[index].id,
    enabled: shouldVirtualize,
    scrollMargin,
  });

  const lastToggleTimeRef = useRef(0);

  virtualizer.shouldAdjustScrollPositionOnItemSizeChange = (
    item,
    _delta,
    instance,
  ) => {
    if (Date.now() - lastToggleTimeRef.current < TOGGLE_SUPPRESS_MS) {
      return false;
    }
    return item.start < (instance.scrollOffset ?? 0);
  };

  const pendingMeasureRef = useRef(false);

  const wrappedHandleToggleAll = useCallback(() => {
    lastToggleTimeRef.current = Date.now();
    pendingMeasureRef.current = true;
    handleToggleAll();
  }, [handleToggleAll]);

  useEffect(() => {
    if (pendingMeasureRef.current) {
      pendingMeasureRef.current = false;
      lastToggleTimeRef.current = Date.now();
      virtualizer.measure();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [expandedMessages]);

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
    [],
  );

  const expandCollapseButton = (
    <Tooltip>
      <TooltipTrigger asChild>
        <Button
          onClick={wrappedHandleToggleAll}
          variant="outline"
          size="icon-2xs"
        >
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
          <div
            className="absolute left-0 top-0 w-full"
            style={{
              transform: `translateY(${
                (virtualizer.getVirtualItems()[0]?.start ?? 0) -
                virtualizer.options.scrollMargin
              }px)`,
            }}
          >
            {virtualizer.getVirtualItems().map((virtualRow) => {
              const message = combinedMessages[virtualRow.index];
              return (
                <div
                  key={message.id}
                  data-index={virtualRow.index}
                  ref={virtualizer.measureElement}
                  className="w-full pb-1"
                >
                  {renderMessage(message)}
                </div>
              );
            })}
          </div>
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
        {combinedMessages.map((message) => renderMessage(message))}
      </PrettyLLMMessage.Container>
    );
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
    </MediaProvider>
  );
};

export default MessagesTab;
