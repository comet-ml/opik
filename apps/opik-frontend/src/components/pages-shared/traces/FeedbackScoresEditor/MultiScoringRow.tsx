import React, { useCallback, useState } from "react";
import { ChevronDown, ChevronRight, Trash, Plus, BarChart3 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { FeedbackScoreGroup, TraceFeedbackScore, FEEDBACK_SCORE_TYPE } from "@/types/traces";
import { FeedbackDefinition } from "@/types/feedback-definitions";
import { cn } from "@/lib/utils";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent } from "@/components/ui/card";
import { formatDistanceToNow } from "date-fns";
import { categoryOptionLabelRenderer } from "@/lib/feedback-scores";
import { FEEDBACK_SCORE_SOURCE_MAP } from "@/lib/feedback-scores";

type MultiScoringRowProps = {
  feedbackScoreGroup: FeedbackScoreGroup;
  feedbackDefinition?: FeedbackDefinition;
  onAddScore: (name: string) => void;
  onDeleteScore: (scoreId: string) => void;
  entityId: string;
  entityType: "trace" | "span";
  workspaceName: string;
};

const MultiScoringRow: React.FunctionComponent<MultiScoringRowProps> = ({
  feedbackScoreGroup,
  feedbackDefinition,
  onAddScore,
  onDeleteScore,
  entityId,
  entityType,
  workspaceName,
}) => {
  const [isExpanded, setIsExpanded] = useState(false);

  const handleToggleExpanded = useCallback(() => {
    setIsExpanded(!isExpanded);
  }, [isExpanded]);

  const handleAddScore = useCallback(() => {
    onAddScore(feedbackScoreGroup.name);
  }, [feedbackScoreGroup.name, onAddScore]);

  const handleDeleteScore = useCallback((scoreId: string) => {
    onDeleteScore(scoreId);
  }, [onDeleteScore]);

  const renderScoreValue = (score: TraceFeedbackScore) => {
    if (feedbackDefinition?.details?.type === "categorical") {
      const category = feedbackDefinition.details.categories?.find(
        (cat) => cat.value === score.value
      );
      return category ? categoryOptionLabelRenderer(category) : score.value;
    }
    return score.value;
  };

  const renderScoreSource = (source: FEEDBACK_SCORE_TYPE) => {
    return (
      <Badge variant="secondary" className="text-xs">
        {FEEDBACK_SCORE_SOURCE_MAP[source] || source}
      </Badge>
    );
  };

  const renderScoreCard = (score: TraceFeedbackScore) => (
    <Card key={score.score_id} className="mb-2">
      <CardContent className="p-3">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <span className="font-medium">{renderScoreValue(score)}</span>
            {renderScoreSource(score.source)}
            {score.category_name && (
              <Badge variant="outline" className="text-xs">
                {score.category_name}
              </Badge>
            )}
          </div>
          <div className="flex items-center gap-2">
            {score.reason && (
              <span className="text-sm text-muted-foreground max-w-48 truncate">
                {score.reason}
              </span>
            )}
            {score.last_updated_by && (
              <span className="text-xs text-muted-foreground">
                by {score.last_updated_by}
              </span>
            )}
            {score.last_updated_at && (
              <span className="text-xs text-muted-foreground">
                {formatDistanceToNow(new Date(score.last_updated_at), { addSuffix: true })}
              </span>
            )}
            <Button
              variant="ghost"
              size="sm"
              onClick={() => handleDeleteScore(score.score_id!)}
              className="h-6 w-6 p-0"
            >
              <Trash className="h-3 w-3" />
            </Button>
          </div>
        </div>
      </CardContent>
    </Card>
  );

  return (
    <div className="border-b border-border last:border-b-0">
      <div className="flex items-center justify-between py-3">
        <div className="flex items-center gap-2 flex-1">
          <Button
            variant="ghost"
            size="sm"
            onClick={handleToggleExpanded}
            className="h-6 w-6 p-0"
          >
            {isExpanded ? (
              <ChevronDown className="h-4 w-4" />
            ) : (
              <ChevronRight className="h-4 w-4" />
            )}
          </Button>
          
          <div className="flex-1">
            <div className="flex items-center gap-2">
              <span className="font-medium">{feedbackScoreGroup.name}</span>
              <Badge variant="outline" className="text-xs">
                {feedbackScoreGroup.score_count} scores
              </Badge>
            </div>
            
            <div className="flex items-center gap-4 mt-1">
              <div className="flex items-center gap-1">
                <BarChart3 className="h-3 w-3 text-muted-foreground" />
                <span className="text-sm text-muted-foreground">
                  Avg: {feedbackScoreGroup.average_value.toFixed(2)}
                </span>
              </div>
              <span className="text-sm text-muted-foreground">
                Min: {feedbackScoreGroup.min_value.toFixed(2)}
              </span>
              <span className="text-sm text-muted-foreground">
                Max: {feedbackScoreGroup.max_value.toFixed(2)}
              </span>
            </div>
          </div>
        </div>
        
        <Button
          variant="outline"
          size="sm"
          onClick={handleAddScore}
          className="ml-2"
        >
          <Plus className="h-3 w-3 mr-1" />
          Add Score
        </Button>
      </div>
      
      {isExpanded && (
        <div className="pl-6 pb-3">
          <div className="space-y-2">
            {feedbackScoreGroup.scores.map(renderScoreCard)}
          </div>
        </div>
      )}
    </div>
  );
};

export default MultiScoringRow;