import React, { useEffect, useMemo } from "react";
import { StringParam, useQueryParam } from "use-query-params";
import sortBy from "lodash/sortBy";

import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/ui/tabs";
import useAnnotationQueueById from "@/api/annotation-queues/useAnnotationQueueById";
import useBreadcrumbsStore from "@/store/BreadcrumbsStore";
import { useAnnotationQueueIdFromURL } from "@/hooks/useAnnotationQueueIdFromURL";
import DateTag from "@/shared/DateTag/DateTag";
import { RESOURCE_TYPE } from "@/shared/ResourceLink/ResourceLink";
import NavigationTag from "@/shared/NavigationTag";
import FeedbackScoresList from "@/v2/pages-shared/FeedbackScoresList/FeedbackScoresList";
import ConfigurationTab from "@/v2/pages/AnnotationQueuePage/ConfigurationTab/ConfigurationTab";
import QueueItemsTab from "@/v2/pages/AnnotationQueuePage/QueueItemsTab/QueueItemsTab";
import PageBodyScrollContainer from "@/v2/layout/PageBodyScrollContainer/PageBodyScrollContainer";
import PageBodyStickyContainer from "@/shared/PageBodyStickyContainer/PageBodyStickyContainer";
import CopySMELinkButton from "@/v2/pages/AnnotationQueuePage/CopySMELinkButton";
import OpenSMELinkButton from "@/v2/pages/AnnotationQueuePage/OpenSMELinkButton";
import EditAnnotationQueueButton from "@/v2/pages/AnnotationQueuePage/EditAnnotationQueueButton";
import ExportAnnotatedDataButton from "@/v2/pages/AnnotationQueuePage/ExportAnnotatedDataButton";
import AnnotationQueueProgressTag from "@/v2/pages/AnnotationQueuePage/AnnotationQueueProgressTag";
import { ANNOTATION_QUEUE_SCOPE } from "@/types/annotation-queues";
import { SCORE_TYPE_FEEDBACK } from "@/types/shared";
import { getScoreDisplayName } from "@/lib/feedback-scores";
import { generateAnnotationQueueIdFilter } from "@/lib/filters";

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

    const filtered = annotationQueue.feedback_scores
      .filter((score) =>
        annotationQueue.feedback_definition_names.includes(score.name),
      )
      .map((score) => ({
        ...score,
        colorKey: score.name,
        name: getScoreDisplayName(score.name, SCORE_TYPE_FEEDBACK),
      }));

    return sortBy(filtered, "name");
  }, [
    annotationQueue?.feedback_scores,
    annotationQueue?.feedback_definition_names,
  ]);

  const annotationQueueSearch = useMemo(() => {
    return {
      [`${annotationQueue?.scope}s_filters`]: generateAnnotationQueueIdFilter(
        annotationQueue?.id,
      ),
    };
  }, [annotationQueue?.scope, annotationQueue?.id]);

  return (
    <PageBodyScrollContainer>
      <PageBodyStickyContainer
        className="mb-4 mt-6 flex min-h-8 items-center justify-between"
        direction="horizontal"
      >
        <h1 className="comet-title-l truncate break-words">{queueName}</h1>
        {annotationQueue && (
          <div className="flex items-center gap-2">
            <CopySMELinkButton annotationQueue={annotationQueue} />
            <EditAnnotationQueueButton annotationQueue={annotationQueue} />
            <ExportAnnotatedDataButton annotationQueue={annotationQueue} />
            <OpenSMELinkButton annotationQueue={annotationQueue} />
          </div>
        )}
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
        <div className="mb-1 flex gap-2 overflow-x-auto">
          {annotationQueue?.created_at && (
            <DateTag
              date={annotationQueue.created_at}
              resource={RESOURCE_TYPE.annotationQueue}
            />
          )}
          {annotationQueue?.scope && annotationQueue?.project_id && (
            <NavigationTag
              resource={
                annotationQueue.scope === ANNOTATION_QUEUE_SCOPE.TRACE
                  ? RESOURCE_TYPE.traces
                  : RESOURCE_TYPE.threads
              }
              id={annotationQueue.project_id}
              name={
                annotationQueue.scope === ANNOTATION_QUEUE_SCOPE.TRACE
                  ? "Go to traces"
                  : "Go to threads"
              }
              search={annotationQueueSearch}
              tooltipContent={`View all ${annotationQueue.scope}s for this queue`}
            />
          )}
          {annotationQueue?.project_id && (
            <NavigationTag
              id={annotationQueue.project_id}
              name={`Go to ${annotationQueue.project_name}`}
              resource={RESOURCE_TYPE.project}
            />
          )}
          {annotationQueue && (
            <AnnotationQueueProgressTag annotationQueue={annotationQueue} />
          )}
        </div>
        <FeedbackScoresList scores={allocatedFeedbackScores} />
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
