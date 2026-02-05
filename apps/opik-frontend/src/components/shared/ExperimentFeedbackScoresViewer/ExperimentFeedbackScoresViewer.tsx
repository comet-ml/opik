import React, { useState } from "react";
import { TraceFeedbackScore } from "@/types/traces";
import useTraceFeedbackScoreDeleteMutation from "@/api/traces/useTraceFeedbackScoreDeleteMutation";
import ExpandableSection from "../ExpandableSection/ExpandableSection";
import { PenLine } from "lucide-react";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import useTraceFeedbackScoreSetMutation from "@/api/traces/useTraceFeedbackScoreSetMutation";
import { UpdateFeedbackScoreData } from "@/components/pages-shared/traces/TraceDetailsPanel/TraceAnnotateViewer/types";
import FeedbackScoreTable from "@/components/pages-shared/traces/TraceDetailsPanel/TraceDataViewer/FeedbackScoreTable/FeedbackScoreTable";
import FeedbackScoresEditor from "@/components/pages-shared/traces/FeedbackScoresEditor/FeedbackScoresEditor";

const FEEDBACK_SCORES_TABS = {
  ALL_SCORES: "all-scores",
  YOUR_SCORES: "your-scores",
} as const;

type ExperimentFeedbackScoresViewerProps = {
  feedbackScores: TraceFeedbackScore[];
  traceId: string;
  spanId?: string;
  sectionIdx: number;
};

const ExperimentFeedbackScoresViewer: React.FunctionComponent<
  ExperimentFeedbackScoresViewerProps
> = ({ feedbackScores = [], traceId, spanId, sectionIdx }) => {
  const [activeTab, setActiveTab] = useState<string>(
    FEEDBACK_SCORES_TABS.ALL_SCORES,
  );
  const { mutate: setTraceFeedbackScore } = useTraceFeedbackScoreSetMutation();
  const { mutate: feedbackScoreDelete } = useTraceFeedbackScoreDeleteMutation();

  const onUpdateFeedbackScore = (data: UpdateFeedbackScoreData) => {
    setTraceFeedbackScore({
      ...data,
      traceId,
      spanId,
    });
  };

  const onDeleteFeedbackScore = (name: string, author?: string) => {
    feedbackScoreDelete({ name, traceId, spanId, author });
  };

  return (
    <ExpandableSection
      icon={<PenLine className="size-4" />}
      title="Feedback scores"
      queryParamName="expandedFeedbackScoresSections"
      sectionIdx={sectionIdx}
      count={feedbackScores.length}
      defaultExpanded
    >
      <div className="px-2 pb-4">
        <Tabs value={activeTab} onValueChange={setActiveTab} className="w-full">
          <TabsList variant="underline">
            <TabsTrigger
              value={FEEDBACK_SCORES_TABS.ALL_SCORES}
              variant="underline"
            >
              All scores
            </TabsTrigger>
            <TabsTrigger
              value={FEEDBACK_SCORES_TABS.YOUR_SCORES}
              variant="underline"
            >
              Your scores
            </TabsTrigger>
          </TabsList>

          <TabsContent value={FEEDBACK_SCORES_TABS.ALL_SCORES} className="mt-4">
            <FeedbackScoreTable
              feedbackScores={feedbackScores}
              onDeleteFeedbackScore={onDeleteFeedbackScore}
              onAddHumanReview={() =>
                setActiveTab(FEEDBACK_SCORES_TABS.YOUR_SCORES)
              }
              entityType="experiment"
            />
          </TabsContent>

          <TabsContent
            value={FEEDBACK_SCORES_TABS.YOUR_SCORES}
            className="mt-4"
          >
            <FeedbackScoresEditor
              key={traceId ?? spanId}
              feedbackScores={feedbackScores}
              onUpdateFeedbackScore={onUpdateFeedbackScore}
              onDeleteFeedbackScore={onDeleteFeedbackScore}
              className="px-0"
            />
          </TabsContent>
        </Tabs>
      </div>
    </ExpandableSection>
  );
};

export default ExperimentFeedbackScoresViewer;
