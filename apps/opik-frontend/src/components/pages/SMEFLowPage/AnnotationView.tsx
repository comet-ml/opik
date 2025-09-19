import React from "react";
import { Card } from "@/components/ui/card";
import { Textarea } from "@/components/ui/textarea";
import { Label } from "@/components/ui/label";
import Loader from "@/components/shared/Loader/Loader";
import { Trace, Thread } from "@/types/traces";
import { AnnotationQueue } from "@/types/annotation-queues";
import ProgressIndicator from "./components/ProgressIndicator";
import FeedbackScoresForm from "./components/FeedbackScoresForm";
import NavigationControls from "./components/NavigationControls";
import ItemViewer from "./components/ItemViewer";
import { useAnnotationForm } from "./hooks/useAnnotationForm";
import SMEFlowLayout from "./SMEFlowLayout";

interface AnnotationViewProps {
  annotationQueue: AnnotationQueue;
  unprocessedItems: (Trace | Thread)[];
  processedCount: number;
  currentItem?: Trace | Thread;
  currentIndex: number;
  onPrevious: () => void;
  onSkip: () => void;
  onSubmitNext: () => void;
  isLoading?: boolean;
}

const AnnotationView: React.FunctionComponent<AnnotationViewProps> = ({
  annotationQueue,
  unprocessedItems,
  processedCount,
  currentItem,
  currentIndex,
  onPrevious,
  onSkip,
  onSubmitNext,
  isLoading = false,
}) => {
  const {
    comment,
    feedbackScores,
    isSubmitting,
    isFirstItem,
    isLastItem,
    hasRequiredFeedback,
    isFeedbackDefinitionsLoading,
    handleCommentChange,
    handleScoreChange,
    handleSkip,
    handleSubmitNext,
  } = useAnnotationForm({
    annotationQueue,
    unprocessedItems,
    currentItem,
    currentIndex,
    onPrevious,
    onSkip,
    onSubmitNext,
  });

  if (isLoading || isFeedbackDefinitionsLoading) {
    return (
      <div className="flex h-64 items-center justify-center">
        <Loader />
      </div>
    );
  }

  return (
    <SMEFlowLayout
      header={
        <>
          <h1 className="comet-title-xl">Annotate</h1>
          <ProgressIndicator
            totalItems={unprocessedItems.length}
            processedCount={processedCount}
          />
        </>
      }
      footer={
        <NavigationControls
          isFirstItem={isFirstItem}
          isLastItem={isLastItem}
          hasRequiredFeedback={hasRequiredFeedback}
          isSubmitting={isSubmitting}
          onPrevious={onPrevious}
          onSkip={handleSkip}
          onSubmitNext={handleSubmitNext}
        />
      }
    >
      <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
        {/* Left Column - Item Viewer */}
        <div className="space-y-4">
          <Card className="p-4">
            <h3 className="comet-body-s mb-4 font-medium">Item to annotate</h3>
            <ItemViewer item={currentItem || null} />
          </Card>
        </div>

        {/* Right Column - Feedback Form */}
        <div className="space-y-4">
          {/* Feedback Scores */}
          <FeedbackScoresForm
            feedbackScores={feedbackScores}
            onScoreChange={handleScoreChange}
          />

          {/* Comment Section */}
          <Card className="p-4">
            <div className="space-y-2">
              <Label htmlFor="comment" className="comet-body-s font-medium">
                Comment (optional)
              </Label>
              <Textarea
                id="comment"
                placeholder="Add any additional comments about this item..."
                value={comment}
                onChange={(e) => handleCommentChange(e.target.value)}
                className="min-h-24"
              />
            </div>
          </Card>
        </div>
      </div>
    </SMEFlowLayout>
  );
};

export default AnnotationView;
