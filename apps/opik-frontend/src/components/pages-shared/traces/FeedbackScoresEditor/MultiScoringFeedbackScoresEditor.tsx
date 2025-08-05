import React, { useMemo, useState } from "react";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Button } from "@/components/ui/button";
import { Plus, InfoIcon, ExternalLink } from "lucide-react";
import { Link } from "@tanstack/react-router";
import useAppStore from "@/store/AppStore";
import useFeedbackDefinitionsList from "@/api/feedback-definitions/useFeedbackDefinitionsList";
import { FeedbackScoreGroup, TraceFeedbackScore, FEEDBACK_SCORE_TYPE } from "@/types/traces";
import { FeedbackDefinition } from "@/types/feedback-definitions";
import { sortBy } from "lodash";
import { cn } from "@/lib/utils";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import ExplainerIcon from "@/components/shared/ExplainerIcon/ExplainerIcon";
import { UpdateFeedbackScoreData } from "../TraceDetailsPanel/TraceAnnotateViewer/types";
import AnnotateRow from "../TraceDetailsPanel/TraceAnnotateViewer/AnnotateRow";
import MultiScoringRow from "./MultiScoringRow";
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogTrigger } from "@/components/ui/dialog";
import { Badge } from "@/components/ui/badge";

type MultiScoringFeedbackScoresEditorProps = {
  feedbackScores?: TraceFeedbackScore[];
  feedbackScoreGroups?: FeedbackScoreGroup[];
  className?: string;
  onUpdateFeedbackScore: (update: UpdateFeedbackScoreData) => void;
  onDeleteFeedbackScore: (name: string) => void;
  onDeleteScoreById: (scoreId: string) => void;
  onAddScore: (name: string) => void;
  entityCopy: string;
  entityId: string;
  entityType: "trace" | "span";
  isLoading?: boolean;
};

type FeedbackScoreRow = {
  name: string;
  feedbackDefinition?: FeedbackDefinition;
  feedbackScore?: TraceFeedbackScore;
};

const MultiScoringFeedbackScoresEditor = ({
  feedbackScores = [],
  feedbackScoreGroups = [],
  onUpdateFeedbackScore,
  onDeleteFeedbackScore,
  onDeleteScoreById,
  onAddScore,
  className,
  entityCopy,
  entityId,
  entityType,
  isLoading = false,
}: MultiScoringFeedbackScoresEditorProps) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const [activeTab, setActiveTab] = useState("multi-scoring");
  const [isAddScoreDialogOpen, setIsAddScoreDialogOpen] = useState(false);
  const [selectedScoreName, setSelectedScoreName] = useState<string>("");

  const { data: feedbackDefinitionsData } = useFeedbackDefinitionsList({
    workspaceName,
    page: 1,
    size: 1000,
  });

  const feedbackScoresUI: TraceFeedbackScore[] = useMemo(
    () =>
      feedbackScores.filter((f) => f.source === FEEDBACK_SCORE_TYPE.ui) || [],
    [feedbackScores],
  );

  const feedbackDefinitions: FeedbackDefinition[] = useMemo(
    () => feedbackDefinitionsData?.content || [],
    [feedbackDefinitionsData?.content],
  );

  const rows: FeedbackScoreRow[] = useMemo(() => {
    return sortBy(
      [
        ...feedbackDefinitions.map((feedbackDefinition) => {
          const feedbackScore = feedbackScoresUI.find(
            (feedbackScore) => feedbackScore.name === feedbackDefinition.name,
          );

          return {
            feedbackDefinition,
            feedbackScore,
            name: feedbackDefinition.name,
          };
        }),
      ],
      "name",
    );
  }, [feedbackDefinitions, feedbackScoresUI]);

  const handleAddScore = useMemo(() => {
    return (name: string) => {
      setSelectedScoreName(name);
      setIsAddScoreDialogOpen(true);
    };
  }, []);

  const handleConfirmAddScore = () => {
    onAddScore(selectedScoreName);
    setIsAddScoreDialogOpen(false);
    setSelectedScoreName("");
  };

  const hasMultiScoring = feedbackScoreGroups.some(group => group.score_count > 1);
  const hasSingleScoring = feedbackScoresUI.length > 0;

  return (
    <div className={cn(className)}>
      <div className="flex flex-col px-6">
        <div className="flex items-center justify-between pb-2">
          <div className="flex items-center gap-1">
            <span className="comet-body-s-accented truncate">Human review</span>
            <ExplainerIcon
              {...EXPLAINERS_MAP[EXPLAINER_ID.what_is_human_review]}
            />
          </div>
          
          {hasMultiScoring && (
            <Badge variant="secondary" className="text-xs">
              Multi-scoring enabled
            </Badge>
          )}
        </div>

        <Tabs value={activeTab} onValueChange={setActiveTab} className="w-full">
          <TabsList className="grid w-full grid-cols-2">
            <TabsTrigger value="multi-scoring" disabled={!hasMultiScoring && !hasSingleScoring}>
              Multi-scoring
            </TabsTrigger>
            <TabsTrigger value="single-scoring" disabled={!hasSingleScoring}>
              Single scoring
            </TabsTrigger>
          </TabsList>

          <TabsContent value="multi-scoring" className="mt-4">
            {hasMultiScoring ? (
              <div className="space-y-2">
                {feedbackScoreGroups.map((group) => (
                  <MultiScoringRow
                    key={group.name}
                    feedbackScoreGroup={group}
                    feedbackDefinition={feedbackDefinitions.find(def => def.name === group.name)}
                    onAddScore={handleAddScore}
                    onDeleteScore={onDeleteScoreById}
                    entityId={entityId}
                    entityType={entityType}
                    workspaceName={workspaceName}
                  />
                ))}
              </div>
            ) : (
              <div className="text-center py-8 text-muted-foreground">
                <p>No multi-scoring data available</p>
                <p className="text-sm">Add scores to see them grouped here</p>
              </div>
            )}
          </TabsContent>

          <TabsContent value="single-scoring" className="mt-4">
            <div className="grid max-w-full grid-cols-[minmax(0,5fr)_minmax(0,5fr)__36px_30px] border-b border-border empty:border-transparent">
              {rows.map((row) => (
                <AnnotateRow
                  key={row.name}
                  name={row.name}
                  feedbackDefinition={row.feedbackDefinition}
                  feedbackScore={row.feedbackScore}
                  onUpdateFeedbackScore={onUpdateFeedbackScore}
                  onDeleteFeedbackScore={onDeleteFeedbackScore}
                />
              ))}
            </div>
          </TabsContent>
        </Tabs>

        <div className="comet-body-xs flex gap-1.5 pt-4 text-light-slate">
          <div className="pt-[3px]">
            <InfoIcon className="size-3" />
          </div>
          <div className="leading-relaxed">
            Set up
            <Button
              size="sm"
              variant="link"
              className="comet-body-xs inline-flex h-auto gap-0.5 px-1"
              asChild
            >
              <Link
                to="/$workspaceName/configuration"
                params={{ workspaceName }}
                search={{
                  tab: "feedback-definitions",
                }}
                target="_blank"
                rel="noopener noreferrer"
              >
                custom human review scores
                <ExternalLink className="size-3" />
              </Link>
            </Button>
            to annotate your {entityCopy}.
          </div>
        </div>
      </div>

      {/* Add Score Dialog */}
      <Dialog open={isAddScoreDialogOpen} onOpenChange={setIsAddScoreDialogOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Add Score for "{selectedScoreName}"</DialogTitle>
          </DialogHeader>
          <div className="space-y-4">
            <p className="text-sm text-muted-foreground">
              You can add a new score for this metric. The new score will be added to the existing group.
            </p>
            <div className="flex justify-end gap-2">
              <Button
                variant="outline"
                onClick={() => setIsAddScoreDialogOpen(false)}
              >
                Cancel
              </Button>
              <Button onClick={handleConfirmAddScore}>
                Add Score
              </Button>
            </div>
          </div>
        </DialogContent>
      </Dialog>
    </div>
  );
};

export default MultiScoringFeedbackScoresEditor;