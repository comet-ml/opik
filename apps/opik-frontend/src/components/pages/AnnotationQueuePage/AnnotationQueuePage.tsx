import React, { useCallback, useEffect, useState } from "react";
import { StringParam, useQueryParam } from "use-query-params";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Tag } from "@/components/ui/tag";
import { Tabs, TabsList, TabsTrigger, TabsContent } from "@/components/ui/tabs";
import Loader from "@/components/shared/Loader/Loader";
import TraceDetailsPanel from "@/components/pages-shared/traces/TraceDetailsPanel/TraceDetailsPanel";
import ThreadDetailsPanel from "@/components/pages-shared/traces/ThreadDetailsPanel/ThreadDetailsPanel";
import AnnotationQueueItemsList from "./AnnotationQueueItemsList";

import useAppStore from "@/store/AppStore";
import useAnnotationQueueById from "@/api/annotation-queues/useAnnotationQueueById";
import useBreadcrumbsStore from "@/store/BreadcrumbsStore";
import { useAnnotationQueueIdFromURL } from "@/hooks/useAnnotationQueueIdFromURL";
import { AnnotationQueueScope } from "@/types/annotation-queues";

const AnnotationQueuePage: React.FunctionComponent = () => {
  const annotationQueueId = useAnnotationQueueIdFromURL();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const setBreadcrumbParam = useBreadcrumbsStore((state) => state.setParam);

  const { data: annotationQueue, isPending } = useAnnotationQueueById({
    workspaceName,
    annotationQueueId: annotationQueueId as string,
  });

  // Sidebar state management
  const [traceId = "", setTraceId] = useQueryParam("trace", StringParam, {
    updateType: "replaceIn",
  });

  const [spanId = "", setSpanId] = useQueryParam("span", StringParam, {
    updateType: "replaceIn",
  });

  const [threadId = "", setThreadId] = useQueryParam("thread", StringParam, {
    updateType: "replaceIn",
  });

  const handleCloseSidebar = useCallback(() => {
    setTraceId("");
    setSpanId("");
    setThreadId("");
  }, [setTraceId, setSpanId, setThreadId]);

  useEffect(() => {
    if (annotationQueue?.name) {
      setBreadcrumbParam("annotationQueueId", annotationQueueId as string, annotationQueue.name);
    }
  }, [annotationQueueId, annotationQueue?.name, setBreadcrumbParam]);

  if (isPending) {
    return <Loader />;
  }

  if (!annotationQueue) {
    return (
      <div className="flex h-full items-center justify-center">
        <div className="text-center">
          <h2 className="text-2xl font-semibold">Annotation queue not found</h2>
          <p className="text-muted-foreground mt-2">
            The annotation queue you're looking for doesn't exist or you don't have access to it.
          </p>
        </div>
      </div>
    );
  }

  return (
    <div className="pt-6">
      <div className="space-y-6">
        {/* Header */}
        <div className="mb-1 flex items-center justify-between">
          <div>
            <h1 className="comet-title-l truncate break-words">{annotationQueue.name}</h1>
            {annotationQueue.description && (
              <p className="text-muted-foreground mt-2">{annotationQueue.description}</p>
            )}
          </div>
          <div className="flex space-x-2">
            <Button variant="outline" size="sm">Edit Queue</Button>
            <Button variant="outline" size="sm" className="text-red-600 hover:text-red-700">
              Delete Queue
            </Button>
          </div>
        </div>

        {/* Tabs */}
        <Tabs defaultValue="items" className="w-full">
          <TabsList variant="underline">
            <TabsTrigger variant="underline" value="items">
              Items ({annotationQueue.items_count || 0})
            </TabsTrigger>
            <TabsTrigger variant="underline" value="configuration">
              Configuration
            </TabsTrigger>
          </TabsList>

          {/* Items Tab */}
          <TabsContent value="items">
            <AnnotationQueueItemsList 
              annotationQueue={annotationQueue}
              onItemClick={(itemId, itemType) => {
                if (annotationQueue.scope === AnnotationQueueScope.TRACE) {
                  setTraceId(itemId);
                  setSpanId("");
                  setThreadId("");
                } else {
                  setThreadId(itemId);
                  setTraceId("");
                  setSpanId("");
                }
              }}
            />
          </TabsContent>

          {/* Configuration Tab */}
          <TabsContent value="configuration">
            <div className="space-y-6">
              {/* Queue Information */}
              <div className="grid gap-6 md:grid-cols-2">
                <Card>
                  <CardHeader>
                    <CardTitle>Queue Details</CardTitle>
                  </CardHeader>
                  <CardContent className="space-y-4">
                    <div>
                      <label className="text-sm font-medium">Scope</label>
                      <div className="mt-1">
                        <Tag className={annotationQueue.scope === AnnotationQueueScope.TRACE ? "" : "bg-secondary"}>
                          {annotationQueue.scope}
                        </Tag>
                      </div>
                    </div>
                    
                    <div>
                      <label className="text-sm font-medium">Comments Enabled</label>
                      <div className="mt-1">
                        <Tag className={annotationQueue.comments_enabled ? "" : "bg-secondary"}>
                          {annotationQueue.comments_enabled ? "Yes" : "No"}
                        </Tag>
                      </div>
                    </div>

                    <div>
                      <label className="text-sm font-medium">Items Count</label>
                      <div className="mt-1 text-2xl font-semibold">
                        {annotationQueue.items_count || 0}
                      </div>
                    </div>

                    {annotationQueue.last_scored_at && (
                      <div>
                        <label className="text-sm font-medium">Last Scored</label>
                        <div className="mt-1">
                          {new Date(annotationQueue.last_scored_at).toLocaleDateString()}
                        </div>
                      </div>
                    )}
                  </CardContent>
                </Card>

                <Card>
                  <CardHeader>
                    <CardTitle>Feedback Definitions</CardTitle>
                  </CardHeader>
                  <CardContent>
                    <div className="space-y-2">
                      {annotationQueue.feedback_definitions?.length > 0 ? (
                        annotationQueue.feedback_definitions.map((definitionId) => (
                          <Tag key={definitionId} className="bg-outline">
                            {definitionId}
                          </Tag>
                        ))
                      ) : (
                        <p className="text-muted-foreground">No feedback definitions configured</p>
                      )}
                    </div>
                  </CardContent>
                </Card>
              </div>

              {/* Instructions */}
              {annotationQueue.instructions && (
                <Card>
                  <CardHeader>
                    <CardTitle>Instructions</CardTitle>
                  </CardHeader>
                  <CardContent>
                    <p className="whitespace-pre-wrap">{annotationQueue.instructions}</p>
                  </CardContent>
                </Card>
              )}

              {/* Reviewers */}
              {annotationQueue.reviewers && annotationQueue.reviewers.length > 0 && (
                <Card>
                  <CardHeader>
                    <CardTitle>Reviewers</CardTitle>
                  </CardHeader>
                  <CardContent>
                    <div className="space-y-2">
                      {annotationQueue.reviewers.map((reviewer) => (
                        <div key={reviewer.username} className="flex items-center justify-between">
                          <span>{reviewer.username}</span>
                          <Tag className="bg-outline">Status: {reviewer.status}</Tag>
                        </div>
                      ))}
                    </div>
                  </CardContent>
                </Card>
              )}

              {/* Aggregated Feedback Scores */}
              {annotationQueue.feedback_scores && annotationQueue.feedback_scores.length > 0 && (
                <Card>
                  <CardHeader>
                    <CardTitle>Feedback Scores Summary</CardTitle>
                  </CardHeader>
                  <CardContent>
                    <div className="space-y-2">
                      {annotationQueue.feedback_scores.map((score) => (
                        <div key={score.name} className="flex items-center justify-between">
                          <span>{score.name}</span>
                          <Tag className="bg-outline">{score.value}</Tag>
                        </div>
                      ))}
                    </div>
                  </CardContent>
                </Card>
              )}
            </div>
          </TabsContent>
        </Tabs>
      </div>

      {/* Sidebar Panels */}
      <TraceDetailsPanel
        projectId={annotationQueue.project_id}
        traceId={traceId!}
        spanId={spanId!}
        setSpanId={setSpanId}
        setThreadId={setThreadId}
        open={Boolean(traceId) && !threadId}
        onClose={handleCloseSidebar}
      />
      <ThreadDetailsPanel
        projectId={annotationQueue.project_id}
        projectName={annotationQueue.project_id} // TODO: Get actual project name
        traceId={traceId!}
        setTraceId={setTraceId}
        threadId={threadId!}
        open={Boolean(threadId)}
        onClose={handleCloseSidebar}
      />
    </div>
  );
};

export default AnnotationQueuePage;
