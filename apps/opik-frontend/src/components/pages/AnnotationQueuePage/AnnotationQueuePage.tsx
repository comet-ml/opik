import React, { useEffect, useMemo } from "react";
import { StringParam, useQueryParam } from "use-query-params";
import capitalize from "lodash/capitalize";
import sortBy from "lodash/sortBy";
import { PenLine } from "lucide-react";

import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Tag } from "@/components/ui/tag";
import useAnnotationQueueById from "@/api/annotation-queues/useAnnotationQueueById";
import useBreadcrumbsStore from "@/store/BreadcrumbsStore";
import { useAnnotationQueueIdFromURL } from "@/hooks/useAnnotationQueueIdFromURL";
import DateTag from "@/components/shared/DateTag/DateTag";
import ResourceLink, {
  RESOURCE_TYPE,
} from "@/components/shared/ResourceLink/ResourceLink";
import FeedbackScoreTag from "@/components/shared/FeedbackScoreTag/FeedbackScoreTag";
import ConfigurationTab from "@/components/pages/AnnotationQueuePage/ConfigurationTab/ConfigurationTab";
import QueueItemsTab from "@/components/pages/AnnotationQueuePage/QueueItemsTab/QueueItemsTab";
import PageBodyScrollContainer from "@/components/layout/PageBodyScrollContainer/PageBodyScrollContainer";
import PageBodyStickyContainer from "@/components/layout/PageBodyStickyContainer/PageBodyStickyContainer";

const AnnotationQueuePage: React.FunctionComponent = () => {
  const [tab = "items", setTab] = useQueryParam("tab", StringParam);

  const annotationQueueId = useAnnotationQueueIdFromURL();
  const setBreadcrumbParam = useBreadcrumbsStore((state) => state.setParam);

  const { data: annotationQueue } = useAnnotationQueueById(
    { annotationQueueId },
    { enabled: !!annotationQueueId },
  );

  const queueName = annotationQueue?.name || "";

  useEffect(() => {
    if (annotationQueueId && queueName) {
      setBreadcrumbParam("annotationQueueId", annotationQueueId, queueName);
    }
  }, [annotationQueueId, queueName, setBreadcrumbParam]);

  const allocatedFeedbackScores = useMemo(() => {
    if (
      !annotationQueue?.feedback_scores ||
      !annotationQueue?.feedback_definition_names
    ) {
      return [];
    }

    const filtered = annotationQueue.feedback_scores.filter((score) =>
      annotationQueue.feedback_definition_names.includes(score.name),
    );

    return sortBy(filtered, "name");
  }, [
    annotationQueue?.feedback_scores,
    annotationQueue?.feedback_definition_names,
  ]);

  return (
    <PageBodyScrollContainer>
      <PageBodyStickyContainer
        className="mb-4 mt-6 flex min-h-8 items-center justify-between"
        direction="horizontal"
      >
        <h1 className="comet-title-l truncate break-words">{queueName}</h1>
      </PageBodyStickyContainer>
      {annotationQueue?.description && (
        <PageBodyStickyContainer
          className="-mt-3 mb-4 flex min-h-8 items-center justify-between"
          direction="horizontal"
        >
          <div className="text-muted-foreground">
            {annotationQueue.description}
          </div>
        </PageBodyStickyContainer>
      )}
      <PageBodyStickyContainer
        className="pb-4"
        direction="horizontal"
        limitWidth
      >
        <div className="mb-1 flex gap-4 overflow-x-auto">
          {annotationQueue?.created_at && (
            <DateTag date={annotationQueue.created_at} />
          )}
          {annotationQueue?.scope && (
            <Tag variant="blue" size="md">
              {capitalize(annotationQueue.scope)}
            </Tag>
          )}
          {annotationQueue?.project_id && (
            <ResourceLink
              id={annotationQueue.project_id}
              name={annotationQueue.project_name}
              resource={RESOURCE_TYPE.project}
              asTag
            />
          )}
        </div>
        <div className="flex h-11 items-center gap-2">
          <PenLine className="size-4 shrink-0" />
          <div className="flex gap-1 overflow-x-auto">
            {allocatedFeedbackScores.map((feedbackScore) => (
              <FeedbackScoreTag
                key={feedbackScore.name + feedbackScore.value}
                label={feedbackScore.name}
                value={feedbackScore.value}
              />
            ))}
          </div>
        </div>
      </PageBodyStickyContainer>
      <Tabs
        defaultValue="items"
        value={tab as string}
        onValueChange={setTab}
        className="min-w-min"
      >
        <PageBodyStickyContainer direction="horizontal" limitWidth>
          <TabsList variant="underline">
            <TabsTrigger variant="underline" value="items">
              Queue items
            </TabsTrigger>
            <TabsTrigger variant="underline" value="configuration">
              Configuration
            </TabsTrigger>
          </TabsList>
        </PageBodyStickyContainer>
        <TabsContent value="items">
          <QueueItemsTab annotationQueue={annotationQueue} />
        </TabsContent>
        <TabsContent value="configuration">
          <ConfigurationTab annotationQueue={annotationQueue} />
        </TabsContent>
      </Tabs>
    </PageBodyScrollContainer>
  );
};

export default AnnotationQueuePage;
