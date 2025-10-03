import React from "react";
import {
  HoverCard,
  HoverCardContent,
  HoverCardTrigger,
} from "@/components/ui/hover-card";
import { AnnotationQueue } from "@/types/annotation-queues";

interface AnnotationQueueProgressProps {
  annotationQueue: AnnotationQueue;
  children: (progressInfo: {
    averageProgress: number;
    progressPercentage: number;
    itemsCount: number;
  }) => React.ReactNode;
}

const AnnotationQueueProgress: React.FunctionComponent<
  AnnotationQueueProgressProps
> = ({ annotationQueue, children }) => {
  const { items_count: itemsCount, reviewers } = annotationQueue;

  if (!reviewers || reviewers.length === 0) {
    return null;
  }

  const averageProgress =
    reviewers.reduce((sum, reviewer) => sum + reviewer.status, 0) /
    reviewers.length;
  const progressPercentage =
    itemsCount > 0 ? Math.round((averageProgress / itemsCount) * 100) : 0;

  return (
    <HoverCard>
      <HoverCardTrigger asChild>
        {children({
          averageProgress: Math.round(averageProgress),
          progressPercentage,
          itemsCount,
        })}
      </HoverCardTrigger>
      <HoverCardContent className="w-64">
        <div className="space-y-2">
          <h4 className="comet-title-xs">Progress by Reviewer</h4>
          <div className="space-y-1">
            {reviewers.map((reviewer) => {
              const reviewerProgress =
                itemsCount > 0
                  ? Math.round((reviewer.status / itemsCount) * 100)
                  : 0;
              return (
                <div
                  key={reviewer.username}
                  className="flex items-center justify-between"
                >
                  <span className="comet-body-s">{reviewer.username}</span>
                  <span className="comet-body-s text-muted-foreground">
                    {reviewer.status}/{itemsCount} ({reviewerProgress}%)
                  </span>
                </div>
              );
            })}
          </div>
        </div>
      </HoverCardContent>
    </HoverCard>
  );
};

export default AnnotationQueueProgress;
