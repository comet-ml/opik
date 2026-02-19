import React, { useCallback, useMemo, useRef } from "react";
import get from "lodash/get";
import isNumber from "lodash/isNumber";
import { StringParam, useQueryParam } from "use-query-params";
import {
  Brain,
  Calendar,
  Clock,
  Coins,
  Hash,
  MessageSquareMore,
  PenLine,
} from "lucide-react";

import { AgentGraphData, Span, Trace } from "@/types/traces";
import {
  METADATA_AGENT_GRAPH_KEY,
  TRACE_TYPE_FOR_TREE,
} from "@/constants/traces";
import BaseTraceDataTypeIcon from "../BaseTraceDataTypeIcon";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import FeedbackScoreHoverCard from "@/components/shared/FeedbackScoreTag/FeedbackScoreHoverCard";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import TagList from "../TagList/TagList";
import MessagesTab from "./MessagesTab";
import DetailsTab from "./DetailsTab";
import AgentGraphTab from "./AgentGraphTab";
import PromptsTab from "./PromptsTab";
import { formatDuration, formatDate } from "@/lib/date";
import isUndefined from "lodash/isUndefined";
import { formatCost } from "@/lib/money";
import TraceDataViewerActionsPanel from "@/components/pages-shared/traces/TraceDetailsPanel/TraceDataViewer/TraceDataViewerActionsPanel";
import UserCommentHoverList from "@/components/pages-shared/traces/UserComment/UserCommentHoverList";
import {
  DetailsActionSection,
  DetailsActionSectionValue,
} from "@/components/pages-shared/traces/DetailsActionSection";
import TraceDataViewerHeader from "./TraceDataViewerHeader";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import ExplainerIcon from "@/components/shared/ExplainerIcon/ExplainerIcon";
import useTraceFeedbackScoreDeleteMutation from "@/api/traces/useTraceFeedbackScoreDeleteMutation";
import ConfigurableFeedbackScoreTable from "./FeedbackScoreTable/ConfigurableFeedbackScoreTable";
import { detectLLMMessages } from "@/components/shared/PrettyLLMMessage/llmMessages";
import { useIsFeatureEnabled } from "@/components/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import { useUnifiedMedia } from "@/hooks/useUnifiedMedia";

type TraceDataViewerProps = {
  graphData?: AgentGraphData;
  data: Trace | Span;
  projectId: string;
  traceId: string;
  spanId?: string;
  activeSection: DetailsActionSectionValue | null;
  setActiveSection: (v: DetailsActionSectionValue) => void;
  isSpansLazyLoading: boolean;
  search?: string;
};

const TraceDataViewer: React.FunctionComponent<TraceDataViewerProps> = ({
  graphData,
  data,
  projectId,
  traceId,
  spanId,
  activeSection,
  setActiveSection,
  isSpansLazyLoading,
  search,
}) => {
  const rootScrollRef = useRef<HTMLDivElement>(null);
  const type = get(data, "type", TRACE_TYPE_FOR_TREE);
  const tokens = data.usage?.total_tokens;

  const agentGraphData = get(
    data,
    ["metadata", METADATA_AGENT_GRAPH_KEY],
    null,
  );
  const hasSpanAgentGraph =
    Boolean(agentGraphData) && type !== TRACE_TYPE_FOR_TREE;
  const showOptimizerPrompts = useIsFeatureEnabled(
    FeatureToggleKeys.OPTIMIZATION_STUDIO_ENABLED,
  );

  const hasPrompts = useMemo(() => {
    if (!showOptimizerPrompts) return false;
    const prompts = (data.metadata as Record<string, unknown>)?.opik_prompts;
    return Array.isArray(prompts) && prompts.length > 0;
  }, [data.metadata, showOptimizerPrompts]);

  const { media, transformedInput, transformedOutput } = useUnifiedMedia(data);

  // Show Messages tab when at least one field is supported and neither is invalid
  const canShowMessagesTab = useMemo(() => {
    const input = detectLLMMessages(transformedInput, { fieldType: "input" });
    const output = detectLLMMessages(transformedOutput, {
      fieldType: "output",
    });

    const hasValid = input.supported || output.supported;
    const hasInvalid =
      (!input.supported && !input.empty) ||
      (!output.supported && !output.empty);

    return hasValid && !hasInvalid;
  }, [transformedInput, transformedOutput]);

  const defaultTab = canShowMessagesTab ? "messages" : "details";

  const [tab, setTab] = useQueryParam("traceTab", StringParam, {
    updateType: "replaceIn",
  });

  const selectedTab = useMemo(() => {
    if (!tab) return defaultTab;

    // Normalize legacy tab values
    const legacyTabMap: Record<string, string> = {
      input: canShowMessagesTab ? "messages" : "details",
      metadata: "details",
    };
    const normalizedTab = legacyTabMap[tab] ?? tab;

    // Fall back when a tab is not available
    if (normalizedTab === "messages" && !canShowMessagesTab) return "details";
    if (normalizedTab === "graph" && !hasSpanAgentGraph) return defaultTab;
    if (normalizedTab === "prompts" && !hasPrompts) return defaultTab;

    return normalizedTab;
  }, [tab, defaultTab, canShowMessagesTab, hasSpanAgentGraph, hasPrompts]);

  const isSpanInputOutputLoading =
    type !== TRACE_TYPE_FOR_TREE && isSpansLazyLoading;
  const entityType = type === TRACE_TYPE_FOR_TREE ? "trace" : "span";
  const isTrace = type === TRACE_TYPE_FOR_TREE;

  const isTraceType = (data: Trace | Span): data is Trace =>
    type === TRACE_TYPE_FOR_TREE;

  const traceData = isTraceType(data) ? data : undefined;
  const hasSpanFeedbackScores = Boolean(
    traceData?.span_feedback_scores?.length,
  );

  const feedbackScoreDeleteMutation = useTraceFeedbackScoreDeleteMutation();

  const onDeleteFeedbackScore = useCallback(
    (name: string, author?: string, spanIdToDelete?: string) => {
      feedbackScoreDeleteMutation.mutate({
        traceId,
        spanId: spanIdToDelete ?? spanId,
        name,
        author,
      });
    },
    [traceId, spanId, feedbackScoreDeleteMutation],
  );

  const duration = formatDuration(data.duration);
  const start_time = data.start_time
    ? formatDate(data.start_time, { includeSeconds: true })
    : "";
  const end_time = data.end_time
    ? formatDate(data.end_time, { includeSeconds: true })
    : "";
  const created_at = data.created_at ? formatDate(data.created_at) : "";
  const estimatedCost = data.total_estimated_cost;
  const model = get(data, "model", null);
  const provider = get(data, "provider", null);

  const durationTooltip = (
    <div>
      Duration in seconds: {duration}
      <p>
        {start_time}
        {end_time ? ` - ${end_time}` : ""}
      </p>
    </div>
  );

  return (
    <div ref={rootScrollRef} className="size-full max-w-full overflow-auto">
      {graphData && (
        <div className="h-72 min-w-[400px] max-w-full overflow-x-hidden border-b p-4">
          <AgentGraphTab data={graphData} />
        </div>
      )}
      <div className="min-w-[400px] max-w-full overflow-x-hidden p-4">
        <div className="mb-6 flex flex-col gap-1">
          <TraceDataViewerHeader
            title={
              <>
                <BaseTraceDataTypeIcon type={type} />
                <div
                  data-testid="data-viewer-title"
                  className="comet-title-xs truncate"
                >
                  {data?.name}
                </div>
              </>
            }
            actionsPanel={(layoutSize) => (
              <TraceDataViewerActionsPanel
                data={data}
                activeSection={activeSection}
                setActiveSection={setActiveSection}
                layoutSize={layoutSize}
              />
            )}
          />
          <div className="comet-body-s-accented flex w-full flex-wrap items-center gap-3 pl-1 text-muted-slate">
            {created_at && (
              <TooltipWrapper content={`Created at: ${created_at}`}>
                <div
                  className="comet-body-xs-accented flex items-center gap-1 text-muted-slate"
                  data-testid="data-viewer-created-at"
                >
                  <Calendar className="size-3 shrink-0" /> {created_at}
                </div>
              </TooltipWrapper>
            )}
            <TooltipWrapper content={durationTooltip}>
              <div
                className="comet-body-xs-accented flex items-center gap-1 text-muted-slate"
                data-testid="data-viewer-duration"
              >
                <Clock className="size-3 shrink-0" /> {duration}
              </div>
            </TooltipWrapper>
            {isNumber(tokens) && (
              <TooltipWrapper content={`Total amount of tokens: ${tokens}`}>
                <div
                  className="comet-body-xs-accented flex items-center gap-1 text-muted-slate"
                  data-testid="data-viewer-tokens"
                >
                  <Hash className="size-3 shrink-0" /> {tokens}
                </div>
              </TooltipWrapper>
            )}
            {!isUndefined(estimatedCost) && (
              <TooltipWrapper
                content={`Estimated cost ${formatCost(estimatedCost, {
                  modifier: "full",
                })}`}
              >
                <div
                  className="comet-body-xs-accented flex items-center gap-1 text-muted-slate"
                  data-testid="data-viewer-cost"
                >
                  <Coins className="size-3 shrink-0" />{" "}
                  {formatCost(estimatedCost)}
                </div>
              </TooltipWrapper>
            )}
            {Boolean(data.feedback_scores?.length) && (
              <FeedbackScoreHoverCard scores={data.feedback_scores!}>
                <div
                  className="comet-body-xs-accented flex items-center gap-1 text-muted-slate"
                  data-testid="data-viewer-scores"
                >
                  <PenLine className="size-3 shrink-0" />{" "}
                  {data.feedback_scores!.length}
                </div>
              </FeedbackScoreHoverCard>
            )}
            {isTrace &&
              traceData &&
              Boolean(traceData.span_feedback_scores?.length) && (
                <FeedbackScoreHoverCard
                  scores={traceData.span_feedback_scores!}
                >
                  <div
                    className="comet-body-xs-accented flex items-center gap-1 text-muted-slate"
                    data-testid="data-viewer-span-scores"
                  >
                    <PenLine className="size-3 shrink-0" />{" "}
                    {traceData.span_feedback_scores!.length} span scores
                  </div>
                </FeedbackScoreHoverCard>
              )}
            {Boolean(data.comments?.length) && (
              <UserCommentHoverList commentsList={data.comments}>
                <div
                  className="comet-body-xs-accented flex items-center gap-1 text-muted-slate"
                  data-testid="data-viewer-comments"
                >
                  <MessageSquareMore className="size-3 shrink-0" />{" "}
                  {data.comments.length}
                </div>
              </UserCommentHoverList>
            )}
            {(model || provider) && (
              <TooltipWrapper
                content={`Model: ${model || "NA"}, Provider: ${
                  provider || "NA"
                }`}
              >
                <div
                  className="comet-body-xs-accented flex items-center gap-1 text-muted-slate"
                  data-testid="data-viewer-provider-model"
                >
                  <Brain className="size-3 shrink-0" />{" "}
                  <div className="truncate">
                    {provider} {model}
                  </div>
                </div>
              </TooltipWrapper>
            )}
          </div>
          <TagList
            data={data}
            tags={data.tags}
            projectId={projectId}
            traceId={traceId}
            spanId={spanId}
            className="pl-1"
          />
        </div>

        <Tabs
          defaultValue={defaultTab}
          value={selectedTab!}
          onValueChange={setTab}
        >
          <TabsList variant="underline">
            {canShowMessagesTab && (
              <TabsTrigger variant="underline" value="messages">
                Messages
              </TabsTrigger>
            )}
            <TabsTrigger variant="underline" value="details">
              Details
            </TabsTrigger>
            <TabsTrigger variant="underline" value="feedback_scores">
              Feedback scores
              <ExplainerIcon
                className="ml-1"
                {...EXPLAINERS_MAP[EXPLAINER_ID.what_are_feedback_scores]}
              />
            </TabsTrigger>
            {hasPrompts && (
              <TabsTrigger variant="underline" value="prompts">
                Prompts
              </TabsTrigger>
            )}
            {hasSpanAgentGraph && (
              <TabsTrigger variant="underline" value="graph">
                Agent graph
              </TabsTrigger>
            )}
          </TabsList>
          {canShowMessagesTab && (
            <TabsContent value="messages">
              <MessagesTab
                transformedInput={transformedInput}
                transformedOutput={transformedOutput}
                media={media}
                isLoading={isSpanInputOutputLoading}
                scrollContainerRef={rootScrollRef}
              />
            </TabsContent>
          )}
          <TabsContent value="details">
            <DetailsTab
              data={data}
              isLoading={isSpanInputOutputLoading}
              search={search}
            />
          </TabsContent>
          <TabsContent value="feedback_scores">
            <div className="space-y-6">
              <div>
                <ConfigurableFeedbackScoreTable
                  title={isTrace ? "Trace scores" : "Span scores"}
                  feedbackScores={data.feedback_scores}
                  onDeleteFeedbackScore={onDeleteFeedbackScore}
                  onAddHumanReview={() =>
                    setActiveSection(DetailsActionSection.Annotations)
                  }
                  entityType={entityType}
                  isAggregatedSpanScores={false}
                />
              </div>
              {isTrace && hasSpanFeedbackScores && traceData && (
                <div>
                  <ConfigurableFeedbackScoreTable
                    title="Span scores"
                    feedbackScores={traceData.span_feedback_scores}
                    onDeleteFeedbackScore={onDeleteFeedbackScore}
                    onAddHumanReview={() =>
                      setActiveSection(DetailsActionSection.Annotations)
                    }
                    entityType="span"
                    isAggregatedSpanScores={true}
                  />
                </div>
              )}
            </div>
          </TabsContent>
          {hasPrompts && (
            <TabsContent value="prompts">
              <PromptsTab data={data} search={search} />
            </TabsContent>
          )}
          {hasSpanAgentGraph && (
            <TabsContent value="graph">
              <AgentGraphTab data={agentGraphData} />
            </TabsContent>
          )}
        </Tabs>
      </div>
    </div>
  );
};

export default TraceDataViewer;
