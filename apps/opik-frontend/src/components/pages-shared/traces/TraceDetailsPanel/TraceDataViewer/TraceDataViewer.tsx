import React, { useCallback, useMemo } from "react";
import get from "lodash/get";
import isNumber from "lodash/isNumber";
import { StringParam, useQueryParam } from "use-query-params";
import {
  Brain,
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
import InputOutputTab from "./InputOutputTab";
import MetadataTab from "./MatadataTab";
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
  const type = get(data, "type", TRACE_TYPE_FOR_TREE);
  const tokens = data.usage?.total_tokens;

  const agentGraphData = get(
    data,
    ["metadata", METADATA_AGENT_GRAPH_KEY],
    null,
  );
  const hasSpanAgentGraph =
    Boolean(agentGraphData) && type !== TRACE_TYPE_FOR_TREE;

  const hasPrompts = useMemo(() => {
    const prompts = get(data.metadata, "opik_prompts", null);
    if (!prompts) return false;
    if (Array.isArray(prompts)) return (prompts as unknown[]).length > 0;
    return false; // opik_prompts should always be an array
  }, [data.metadata]);

  const [tab = "input", setTab] = useQueryParam("traceTab", StringParam, {
    updateType: "replaceIn",
  });

  const selectedTab =
    (tab === "graph" && !hasSpanAgentGraph) ||
    (tab === "prompts" && !hasPrompts)
      ? "input"
      : tab;

  const isSpanInputOutputLoading =
    type !== TRACE_TYPE_FOR_TREE && isSpansLazyLoading;
  const entityType = type === TRACE_TYPE_FOR_TREE ? "trace" : "span";
  const isTrace = type === TRACE_TYPE_FOR_TREE;

  /**
   * Type guard function to safely check if data is a Trace.
   * Traces have type === "trace" or no type field, while Spans have type in SPAN_TYPE enum.
   */
  const isTraceType = (data: Trace | Span): data is Trace => {
    return type === TRACE_TYPE_FOR_TREE;
  };

  const traceData = isTraceType(data) ? data : undefined;
  const hasSpanFeedbackScores = Boolean(
    traceData?.span_feedback_scores?.length,
  );

  const feedbackScoreDeleteMutation = useTraceFeedbackScoreDeleteMutation();

  const onDeleteFeedbackScore = useCallback(
    (name: string, author?: string, spanIdToDelete?: string) => {
      // If spanIdToDelete is provided (child row grouped by type), delete for that specific span
      if (spanIdToDelete) {
        feedbackScoreDeleteMutation.mutate({
          traceId,
          spanId: spanIdToDelete,
          name,
          author,
        });
      } else {
        // Regular deletion (trace or single span)
        feedbackScoreDeleteMutation.mutate({
          traceId,
          spanId,
          name,
          author,
        });
      }
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
    <div className="size-full max-w-full overflow-auto">
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
          <div className="comet-body-s-accented flex w-full items-center gap-3 overflow-x-hidden text-muted-slate">
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
                content={`Estimated cost ${formatCost(estimatedCost)}`}
              >
                <div
                  className="comet-body-xs-accented flex items-center gap-1 text-muted-slate"
                  data-testid="data-viewer-cost"
                >
                  <Coins className="size-3 shrink-0" />{" "}
                  {formatCost(estimatedCost, { modifier: "short" })}
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
          />
        </div>

        <Tabs defaultValue="input" value={selectedTab!} onValueChange={setTab}>
          <TabsList variant="underline">
            <TabsTrigger variant="underline" value="input">
              Input/Output
            </TabsTrigger>
            <TabsTrigger variant="underline" value="feedback_scores">
              Feedback scores
              <ExplainerIcon
                className="ml-1"
                {...EXPLAINERS_MAP[EXPLAINER_ID.what_are_feedback_scores]}
              />
            </TabsTrigger>
            <TabsTrigger variant="underline" value="metadata">
              Metadata
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
          <TabsContent value="input">
            <InputOutputTab
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
          <TabsContent value="metadata">
            <MetadataTab data={data} search={search} />
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
