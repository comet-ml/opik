import React, { useCallback, useMemo, useRef } from "react";
import get from "lodash/get";
import { StringParam, useQueryParam } from "use-query-params";
import { Brain, Calendar, MessageSquareMore, PenLine } from "lucide-react";

import {
  AgentGraphData,
  BASE_TRACE_DATA_TYPE,
  Span,
  Trace,
} from "@/types/traces";
import {
  METADATA_AGENT_GRAPH_KEY,
  TAG_VARIANT_BY_TRACE_TYPE,
  TRACE_TYPE_FOR_TREE,
} from "@/constants/traces";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import FeedbackScoreHoverCard from "@/shared/FeedbackScoreTag/FeedbackScoreHoverCard";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/ui/tabs";
import TagList from "../TagList/TagList";
import MessagesTab from "./MessagesTab";
import DetailsTab from "./DetailsTab";
import AgentGraphTab from "./AgentGraphTab";
import AgentConfigurationTab, {
  isAgentConfigurationMetadata,
} from "./AgentConfigurationTab";
import { AGENT_CONFIGURATION_METADATA_KEY } from "@/utils/agent-configurations";
import { formatDate } from "@/lib/date";
import TraceStatsDisplay from "@/v2/pages-shared/traces/TraceStatsDisplay/TraceStatsDisplay";
import { usePermissions } from "@/contexts/PermissionsContext";
import UserCommentHoverList from "@/shared/UserComment/UserCommentHoverList";
import {
  DetailsActionSection,
  DetailsActionSectionValue,
} from "@/v2/pages-shared/traces/DetailsActionSection";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/v2/constants/explainers";
import ExplainerIcon from "@/shared/ExplainerIcon/ExplainerIcon";
import useTraceFeedbackScoreDeleteMutation from "@/api/traces/useTraceFeedbackScoreDeleteMutation";
import ConfigurableFeedbackScoreTable from "./FeedbackScoreTable/ConfigurableFeedbackScoreTable";
import { detectLLMMessages } from "@/shared/PrettyLLMMessage/llmMessages";
import { useUnifiedMedia } from "@/hooks/useUnifiedMedia";

type TraceDataViewerProps = {
  graphData?: AgentGraphData;
  data: Trace | Span;
  projectId: string;
  traceId: string;
  spanId?: string;
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
  setActiveSection,
  isSpansLazyLoading,
  search,
}) => {
  const {
    permissions: { canAnnotateTraceSpanThread },
  } = usePermissions();

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
  const hasAgentConfiguration = useMemo(() => {
    const config = (data.metadata as Record<string, unknown>)?.[
      AGENT_CONFIGURATION_METADATA_KEY
    ];
    return isAgentConfigurationMetadata(config);
  }, [data.metadata]);

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
      prompts: "details",
    };
    const normalizedTab = legacyTabMap[tab] ?? tab;

    // Fall back when a tab is not available
    if (normalizedTab === "messages" && !canShowMessagesTab) return "details";
    if (normalizedTab === "graph" && !hasSpanAgentGraph) return defaultTab;
    if (normalizedTab === "configuration" && !hasAgentConfiguration)
      return defaultTab;

    return normalizedTab;
  }, [
    tab,
    defaultTab,
    canShowMessagesTab,
    hasSpanAgentGraph,
    hasAgentConfiguration,
  ]);

  const isSpanInputOutputLoading =
    type !== TRACE_TYPE_FOR_TREE && isSpansLazyLoading;
  const entityType = type === TRACE_TYPE_FOR_TREE ? "trace" : "span";
  const isTrace = type === TRACE_TYPE_FOR_TREE;

  const isTraceType = (_data: Trace | Span): _data is Trace =>
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

  const created_at = data.created_at ? formatDate(data.created_at) : "";
  const model = get(data, "model", null);
  const provider = get(data, "provider", null);

  return (
    <div ref={rootScrollRef} className="size-full max-w-full overflow-auto">
      {graphData && (
        <div className="h-72 min-w-[400px] max-w-full overflow-x-hidden border-b p-4">
          <AgentGraphTab data={graphData} />
        </div>
      )}
      <div className="min-w-[400px] max-w-full overflow-x-hidden p-4">
        <div className="mb-6 flex flex-col gap-1">
          <div className="comet-body-s flex w-full flex-wrap items-center gap-3 pl-1 text-muted-slate">
            {created_at && (
              <TooltipWrapper content={`Created at: ${created_at}`}>
                <div
                  className="comet-body-xs flex items-center gap-1 text-muted-slate"
                  data-testid="data-viewer-created-at"
                >
                  <Calendar className="size-3 shrink-0" /> {created_at}
                </div>
              </TooltipWrapper>
            )}
            <TraceStatsDisplay
              duration={data.duration}
              startTime={data.start_time}
              endTime={data.end_time}
              totalTokens={tokens}
              estimatedCost={data.total_estimated_cost}
            />
            {Boolean(data.feedback_scores?.length) && (
              <FeedbackScoreHoverCard scores={data.feedback_scores!}>
                <div
                  className="comet-body-xs flex items-center gap-1 text-muted-slate"
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
                    className="comet-body-xs flex items-center gap-1 text-muted-slate"
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
                  className="comet-body-xs flex items-center gap-1 text-muted-slate"
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
                  className="comet-body-xs flex items-center gap-1 text-muted-slate"
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
            tagVariant={
              TAG_VARIANT_BY_TRACE_TYPE[type as BASE_TRACE_DATA_TYPE] ?? "gray"
            }
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
            {hasSpanAgentGraph && (
              <TabsTrigger variant="underline" value="graph">
                Agent graph
              </TabsTrigger>
            )}
            {hasAgentConfiguration && (
              <TabsTrigger variant="underline" value="configuration">
                Configuration
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
                  onDeleteFeedbackScore={
                    canAnnotateTraceSpanThread
                      ? onDeleteFeedbackScore
                      : undefined
                  }
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
                    onDeleteFeedbackScore={
                      canAnnotateTraceSpanThread
                        ? onDeleteFeedbackScore
                        : undefined
                    }
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
          {hasSpanAgentGraph && (
            <TabsContent value="graph">
              <AgentGraphTab data={agentGraphData} />
            </TabsContent>
          )}
          {hasAgentConfiguration && (
            <TabsContent value="configuration">
              <AgentConfigurationTab data={data} projectId={projectId} />
            </TabsContent>
          )}
        </Tabs>
      </div>
    </div>
  );
};

export default TraceDataViewer;
