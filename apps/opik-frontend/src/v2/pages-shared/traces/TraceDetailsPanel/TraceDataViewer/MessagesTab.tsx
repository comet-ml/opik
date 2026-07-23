import React, {
  useRef,
  useMemo,
  useCallback,
  useState,
  useEffect,
} from "react";
import { useVirtualizer } from "@tanstack/react-virtual";
import { ChevronRight, FoldVertical, UnfoldVertical } from "lucide-react";
import { UnifiedMediaItem } from "@/hooks/useUnifiedMedia";
import {
  MediaProvider,
  mapAndCombineMessages,
  LLMMessageDescriptor,
  LLMBlockDescriptor,
} from "@/shared/PrettyLLMMessage/llmMessages";
import { Button } from "@/ui/button";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/ui/tooltip";
import CopyButton from "@/shared/CopyButton/CopyButton";
import PrettyLLMMessage from "@/shared/PrettyLLMMessage";
import { useLLMMessagesExpandAll } from "@/shared/SyntaxHighlighter/hooks/useSyntaxHighlighterHooks";
import Loader from "@/shared/Loader/Loader";
import CollapsibleSection from "@/v2/pages-shared/traces/TraceDetailsPanel/CollapsibleSection";

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
  // Count of leading input messages already presented to the viewer in an earlier span of the
  // same session (autonomic-opik-export's metadata.already_shown_count, or any exporter
  // populating the same key) -- hidden by default behind a single disclosure row, independent of
  // per-message accordion state and the Expand/Collapse-all toggle (see AlreadyShownDisclosure).
  // Provider-agnostic: every registered format mapper (openai/langchain/anthropic) ids input
  // messages "input-{index}" by position in the raw input message array, so this maps directly
  // without needing to know which format matched.
  unchangedPrefixLength?: number;
};

type DisplayItem =
  | { kind: "message"; message: LLMMessageDescriptor }
  | { kind: "disclosure"; count: number };

const isMessageItem = (
  item: DisplayItem,
): item is Extract<DisplayItem, { kind: "message" }> => item.kind === "message";

const ALREADY_SHOWN_DISCLOSURE_ID = "already-shown-disclosure";

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

const AlreadyShownDisclosure: React.FC<{
  count: number;
  onReveal: () => void;
}> = ({ count, onReveal }) => (
  <button
    type="button"
    onClick={onReveal}
    className="comet-body-xs-accented flex w-full select-none items-center gap-1 rounded-sm p-1 text-muted-slate transition-colors hover:bg-primary-foreground"
  >
    <ChevronRight className="size-3.5 shrink-0 text-light-slate" />
    {count} earlier message{count === 1 ? "" : "s"} already shown in a previous
    turn — click to view
  </button>
);

const MessagesTab: React.FunctionComponent<MessagesTabProps> = ({
  transformedInput,
  transformedOutput,
  media,
  isLoading,
  scrollContainerRef,
  unchangedPrefixLength,
}) => {
  const { messages: combinedMessages, usage } = useMemo(
    () => mapAndCombineMessages(transformedInput, transformedOutput),
    [transformedInput, transformedOutput],
  );

  // Ids of the leading input messages already presented to the viewer on an earlier span --
  // hidden behind a single disclosure row rather than collapsed individually (see
  // AlreadyShownDisclosure), so neither Expand/Collapse-all nor the persisted expand-state
  // preference (shared across every trace, see useLLMMessagesExpandAll) can silently reveal or
  // re-hide them.
  const alreadyShownIdSet = useMemo(() => {
    if (!unchangedPrefixLength || unchangedPrefixLength <= 0) return null;
    const ids = new Set<string>();
    for (let i = 0; i < unchangedPrefixLength; i += 1) ids.add(`input-${i}`);
    return ids;
  }, [unchangedPrefixLength]);

  const [alreadyShownRevealed, setAlreadyShownRevealed] = useState(false);

  const displayItems = useMemo<DisplayItem[]>(() => {
    if (!alreadyShownIdSet || alreadyShownRevealed) {
      return combinedMessages.map(
        (message): DisplayItem => ({ kind: "message", message }),
      );
    }
    const items: DisplayItem[] = [];
    let disclosureAdded = false;
    combinedMessages.forEach((message) => {
      if (alreadyShownIdSet.has(message.id)) {
        if (!disclosureAdded) {
          items.push({ kind: "disclosure", count: alreadyShownIdSet.size });
          disclosureAdded = true;
        }
        return;
      }
      items.push({ kind: "message", message });
    });
    return items;
  }, [combinedMessages, alreadyShownIdSet, alreadyShownRevealed]);

  const allMessageIds = useMemo(
    () => displayItems.filter(isMessageItem).map((item) => item.message.id),
    [displayItems],
  );

  const defaultCollapsedIds = useMemo(
    () =>
      displayItems
        .filter(isMessageItem)
        .filter((item) => item.message.role === "system")
        .map((item) => item.message.id),
    [displayItems],
  );

  const {
    isAllExpanded,
    expandedMessages,
    handleToggleAll,
    handleValueChange,
  } = useLLMMessagesExpandAll(
    allMessageIds,
    "messages-tab-combined",
    defaultCollapsedIds,
  );

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

  const shouldVirtualize = displayItems.length > VIRTUALIZATION_THRESHOLD;

  const internalScrollRef = useRef<HTMLDivElement>(null);
  const effectiveScrollRef = scrollContainerRef ?? internalScrollRef;

  const [scrollMargin, setScrollMargin] = useState(0);

  const listRef = useCallback((node: HTMLDivElement | null) => {
    if (node) {
      setScrollMargin(node.offsetTop);
    }
  }, []);

  const getDisplayItemKey = useCallback(
    (item: DisplayItem) =>
      isMessageItem(item) ? item.message.id : ALREADY_SHOWN_DISCLOSURE_ID,
    [],
  );

  const virtualizer = useVirtualizer({
    count: displayItems.length,
    getScrollElement: () => effectiveScrollRef.current,
    estimateSize: (index) => {
      const item = displayItems[index];
      if (!item) return ESTIMATED_COLLAPSED_HEIGHT;
      return isMessageItem(item) && expandedSet.has(item.message.id)
        ? ESTIMATED_EXPANDED_HEIGHT
        : ESTIMATED_COLLAPSED_HEIGHT;
    },
    overscan: VIRTUAL_OVERSCAN,
    getItemKey: (index) => getDisplayItemKey(displayItems[index]),
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

  const renderDisplayItem = useCallback((item: DisplayItem) => {
    if (!isMessageItem(item)) {
      return (
        <AlreadyShownDisclosure
          key={ALREADY_SHOWN_DISCLOSURE_ID}
          count={item.count}
          onReveal={() => setAlreadyShownRevealed(true)}
        />
      );
    }
    const { message } = item;
    return (
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
    );
  }, []);

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
              const item = displayItems[virtualRow.index];
              return (
                <div
                  key={getDisplayItemKey(item)}
                  data-index={virtualRow.index}
                  ref={virtualizer.measureElement}
                  className="w-full pb-1"
                >
                  {renderDisplayItem(item)}
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
        {displayItems.map((item) => renderDisplayItem(item))}
      </PrettyLLMMessage.Container>
    );
  }

  return (
    <MediaProvider media={media}>
      <CollapsibleSection
        title="LLM messages"
        actions={
          <>
            {displayItems.length > 0 && expandCollapseButton}
            {copyButton}
          </>
        }
        bodyClassName="px-2 pb-2 pt-1"
      >
        {displayItems.length > 0 ? (
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
      </CollapsibleSection>
    </MediaProvider>
  );
};

export default MessagesTab;
