import React, { useMemo } from "react";
import { AnnotationQueue } from "@/types/annotation-queues";
import useFeedbackDefinitionsList from "@/api/feedback-definitions/useFeedbackDefinitionsList";
import useAppStore from "@/store/AppStore";
import ScoresContent from "@/components/pages-shared/annotation-queues/ScoresContent";

interface ScoresSectionProps {
  annotationQueue: AnnotationQueue;
}

const ScoresSection: React.FunctionComponent<ScoresSectionProps> = ({
  annotationQueue,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const { data } = useFeedbackDefinitionsList(
    {
      workspaceName,
      page: 1,
      size: 1000,
    },
    {
      enabled: annotationQueue.feedback_definition_names.length > 0,
    },
  );

  const feedbackDefinitions = useMemo(() => {
    if (!data?.content || !annotationQueue.feedback_definition_names.length) {
      return [];
    }

    return data.content.filter((def) =>
      annotationQueue.feedback_definition_names.includes(def.name),
    );
  }, [data?.content, annotationQueue.feedback_definition_names]);

  if (!annotationQueue.feedback_definition_names.length) {
    return null;
  }

  return (
    <div className="pt-6">
      <h2 className="comet-title-s truncate break-words pb-3 pt-2">
        Feedback scores ({feedbackDefinitions.length})
      </h2>
      <ScoresContent annotationQueue={annotationQueue} />
    </div>
  );
};

export default ScoresSection;
