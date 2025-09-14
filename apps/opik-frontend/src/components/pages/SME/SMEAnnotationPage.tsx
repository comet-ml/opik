import React, { useState, useCallback, useMemo } from "react";
import { useNavigate } from "@tanstack/react-router";
import {
  ChevronDown,
  ChevronUp,
  ChevronLeft,
  ChevronRight,
  Copy,
  Info,
  Table,
} from "lucide-react";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import FeedbackScoreTag from "@/components/shared/FeedbackScoreTag/FeedbackScoreTag";
import Loader from "@/components/shared/Loader/Loader";

import {
  SMEAnnotationQueue,
  AnnotationQueueScope,
} from "@/types/annotation-queues";
import useSMEQueueItems from "@/api/annotation-queues/useSMEQueueItems";
import useSMEProgress from "@/api/annotation-queues/useSMEProgress";
import useSMEAnnotationMutation from "@/api/annotation-queues/useSMEAnnotationMutation";
import useSMEQueueItemData from "@/api/annotation-queues/useSMEQueueItemData";
import useSMEIndividualProgress from "@/api/annotation-queues/useSMEIndividualProgress";
import useFeedbackDefinitionsList from "@/api/feedback-definitions/useFeedbackDefinitionsList";
import useAppStore from "@/store/AppStore";
import { formatDate } from "@/lib/date";
import imageLogoUrl from "/images/opik-logo.png";

type SMEAnnotationPageProps = {
  queue: SMEAnnotationQueue;
  shareToken: string;
};

const SMEAnnotationPage: React.FunctionComponent<SMEAnnotationPageProps> = ({
  queue,
  shareToken,
}) => {
  const navigate = useNavigate();
  const mutation = useSMEAnnotationMutation();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const [currentItemIndex, setCurrentItemIndex] = useState(0);
  const [inputExpanded, setInputExpanded] = useState(true);
  const [outputExpanded, setOutputExpanded] = useState(true);
  const [metadataExpanded, setMetadataExpanded] = useState(false);
  const [comment, setComment] = useState("");
  const [feedbackScores, setFeedbackScores] = useState<Record<string, number>>(
    {},
  );

  // Generate or retrieve SME identifier for progress tracking
  const smeId = useMemo(() => {
    const stored = localStorage.getItem(`sme-id-${shareToken}`);
    if (stored) return stored;

    const newId = `sme-${Date.now()}-${Math.random()
      .toString(36)
      .substr(2, 9)}`;
    localStorage.setItem(`sme-id-${shareToken}`, newId);
    return newId;
  }, [shareToken]);

  const { data: itemsData, isPending: isItemsLoading } = useSMEQueueItems({
    shareToken,
    page: 1,
    size: 1000, // Get all items for now - TODO: implement pagination for large queues
  });

  const { data: progress } = useSMEProgress({
    shareToken,
  });

  // Get individual SME progress
  const { data: individualProgress } = useSMEIndividualProgress({
    shareToken,
    smeId,
  });

  const items = itemsData?.content || [];
  const currentItem = items[currentItemIndex];

  // Fetch current item data for annotation
  const { data: currentItemData, isPending: isItemDataLoading } =
    useSMEQueueItemData(
      {
        shareToken,
        itemId: currentItem?.id as string,
      },
      {
        enabled: !!currentItem?.id,
      },
    );

  // Fetch feedback definitions to get names and descriptions
  const { data: feedbackDefinitionsData } = useFeedbackDefinitionsList({
    workspaceName,
    page: 1,
    size: 1000, // Get all feedback definitions
  });

  const feedbackDefinitions = useMemo(
    () => feedbackDefinitionsData?.content ?? [],
    [feedbackDefinitionsData?.content],
  );

  // Map feedback definition IDs to their actual data
  const feedbackDefinitionsMap = useMemo(() => {
    const map = new Map();
    feedbackDefinitions.forEach((def) => {
      map.set(def.id, def);
    });
    return map;
  }, [feedbackDefinitions]);

  const handlePrevious = useCallback(() => {
    if (currentItemIndex > 0) {
      setCurrentItemIndex(currentItemIndex - 1);
      setComment(""); // Reset form for new item
      setFeedbackScores({});
    }
  }, [currentItemIndex]);

  const handleNext = useCallback(() => {
    if (currentItemIndex < items.length - 1) {
      setCurrentItemIndex(currentItemIndex + 1);
      setComment(""); // Reset form for new item
      setFeedbackScores({});
    }
  }, [currentItemIndex, items.length]);

  const handleSkip = useCallback(() => {
    handleNext();
  }, [handleNext]);

  const handleSubmitAndNext = useCallback(() => {
    const submission = {
      feedback_scores: Object.entries(feedbackScores).map(
        ([definitionId, value]) => {
          const definition = feedbackDefinitionsMap.get(definitionId);
          return {
            name: definition?.name || definitionId,
            value,
          };
        },
      ),
      comment,
    };

    mutation.mutate(
      {
        shareToken,
        itemId: currentItem.id as string,
        smeId,
        annotation: submission,
      },
      {
        onSuccess: () => {
          // Reset form and advance
          setComment("");
          setFeedbackScores({});

          // Check if this was the last item
          if (currentItemIndex >= items.length - 1) {
            // This was the last item - wait a moment for progress to update, then check completion
            setTimeout(() => {
              // The progress APIs will be invalidated and refetched
              // If all items are completed, the component will show completion state
            }, 1000);
          } else {
            // Advance to next item
            handleNext();
          }
        },
      },
    );
  }, [
    feedbackScores,
    comment,
    currentItem,
    shareToken,
    smeId,
    mutation,
    handleNext,
    feedbackDefinitionsMap,
  ]);

  const progressData = individualProgress
    ? {
        current: individualProgress.completed_items,
        total: individualProgress.total_items,
      }
    : progress
      ? {
          current: progress.completed_items,
          total: progress.total_items,
        }
      : {
          current: currentItemIndex + 1,
          total: items.length,
        };

  if (isItemsLoading) {
    return (
      <div className="min-h-screen bg-gray-50 flex items-center justify-center">
        <div className="text-center">
          <Loader />
          <p className="text-gray-500 mt-4">Loading items...</p>
        </div>
      </div>
    );
  }

  // Check if queue is completed based on progress
  const isQueueCompleted = individualProgress
    ? individualProgress.completed_items >= individualProgress.total_items
    : progress
      ? progress.completed_items >= progress.total_items
      : false;

  if (!currentItem || isQueueCompleted) {
    return (
      <div className="min-h-screen bg-gray-50 flex items-center justify-center">
        <div className="text-center max-w-md">
          <h2 className="text-2xl font-bold mb-2">All Done!</h2>
          <p className="text-gray-600 mb-4">
            You've completed all items in this annotation queue.
          </p>
          <Button onClick={() => navigate({ to: `/sme/queue/${shareToken}` })}>
            Back to Instructions
          </Button>
        </div>
      </div>
    );
  }

  const renderValue = (value: unknown): React.ReactNode => {
    if (value === null || value === undefined) {
      return <span className="text-gray-400 italic">null</span>;
    }

    if (typeof value === "string") {
      return <span className="text-gray-900">{value}</span>;
    }

    if (typeof value === "number" || typeof value === "boolean") {
      return <span className="text-gray-900">{String(value)}</span>;
    }

    if (typeof value === "object") {
      return (
        <pre className="text-xs text-gray-700 whitespace-pre-wrap">
          {JSON.stringify(value, null, 2)}
        </pre>
      );
    }

    return <span className="text-gray-900">{String(value)}</span>;
  };

  return (
    <div className="min-h-screen bg-gray-50">
      {/* Header with Opik Logo */}
      <header className="bg-white border-b border-gray-200 px-4 py-3">
        <div className="flex items-center">
          <div className="flex items-center">
            <img
              className="h-8 object-cover object-left"
              src={imageLogoUrl}
              alt="opik logo"
            />
          </div>
        </div>
      </header>

      {/* Main Content */}
      <div className="bg-gray-50 min-h-screen px-8 py-10">
        <div className="max-w-6xl mx-auto space-y-10">
          {/* Header Section */}
          <div className="space-y-3">
            <div className="flex items-center justify-between">
              <h1 className="text-2xl font-medium text-gray-900">
                {queue.name}
              </h1>
              <div className="flex items-center space-x-2">
                <Button variant="outline" size="sm">
                  <Table className="mr-2 h-4 w-4" />
                  Go to table view
                </Button>
                <Button variant="outline" size="sm">
                  <Info className="mr-2 h-4 w-4" />
                  Read instructions
                </Button>
              </div>
            </div>

            {/* Progress Section */}
            <div className="space-y-2">
              <div className="flex items-center justify-between">
                <span className="text-sm font-medium text-gray-700">
                  Progress
                </span>
                <span className="text-sm text-gray-500">
                  {progressData.current}/{progressData.total} completed
                </span>
              </div>
              <div className="w-full bg-gray-200 rounded-full h-1.5">
                <div
                  className="bg-blue-600 h-1.5 rounded-full transition-all duration-300"
                  style={{
                    width: `${
                      (progressData.current / progressData.total) * 100
                    }%`,
                  }}
                />
              </div>
            </div>
          </div>

          {/* Content and Feedback - Single Unified Card */}
          <div className="bg-white border border-gray-200 rounded-md p-6">
            <div className="flex gap-6">
              {/* Left Side - Content */}
              <div className="flex-1">
                {/* Input Section */}
                <div className="pb-4 border-b border-gray-200">
                  <Button
                    variant="ghost"
                    className="w-full justify-start p-2 text-left font-normal hover:bg-gray-50 -ml-2"
                    onClick={() => setInputExpanded(!inputExpanded)}
                  >
                    <span className="text-sm font-medium">Input</span>
                  </Button>
                  {inputExpanded && (
                    <div className="mt-2">
                      <div className="bg-slate-50 border border-gray-200 rounded-md">
                        <div className="px-4 py-3 border-b border-gray-200 flex items-center justify-between">
                          <Button
                            variant="outline"
                            size="sm"
                            className="text-xs h-6 px-2 bg-slate-50 border-slate-200 text-slate-600 hover:bg-slate-100"
                          >
                            Pretty ✨
                            <ChevronDown className="ml-1 h-3 w-3" />
                          </Button>
                          <Button
                            variant="outline"
                            size="icon-sm"
                            className="h-6 w-6 bg-slate-50 border-slate-200 text-slate-600 hover:bg-slate-100"
                          >
                            <Copy className="h-3 w-3" />
                          </Button>
                        </div>
                        <div className="p-4">
                          {isItemDataLoading ? (
                            <div className="text-center py-4">
                              <Loader />
                              <p className="text-gray-500 mt-2">
                                Loading item data...
                              </p>
                            </div>
                          ) : (
                            renderValue(
                              currentItemData?.input || currentItem.input,
                            )
                          )}
                        </div>
                      </div>
                    </div>
                  )}
                </div>

                {/* Output Section */}
                <div className="py-4 border-b border-gray-200">
                  <Button
                    variant="ghost"
                    className="w-full justify-start p-2 text-left font-normal hover:bg-gray-50 -ml-2"
                    onClick={() => setOutputExpanded(!outputExpanded)}
                  >
                    <span className="text-sm font-medium">Output</span>
                  </Button>
                  {outputExpanded && (
                    <div className="mt-2">
                      <div className="bg-slate-50 border border-gray-200 rounded-md">
                        <div className="px-4 py-3 border-b border-gray-200 flex items-center justify-between">
                          <Button
                            variant="outline"
                            size="sm"
                            className="text-xs h-6 px-2 bg-slate-50 border-slate-200 text-slate-600 hover:bg-slate-100"
                          >
                            Pretty ✨
                            <ChevronDown className="ml-1 h-3 w-3" />
                          </Button>
                          <Button
                            variant="outline"
                            size="icon-sm"
                            className="h-6 w-6 bg-slate-50 border-slate-200 text-slate-600 hover:bg-slate-100"
                          >
                            <Copy className="h-3 w-3" />
                          </Button>
                        </div>
                        <div className="p-4">
                          {isItemDataLoading ? (
                            <div className="text-center py-4">
                              <Loader />
                              <p className="text-gray-500 mt-2">
                                Loading item data...
                              </p>
                            </div>
                          ) : (
                            renderValue(
                              currentItemData?.output || currentItem.output,
                            )
                          )}
                        </div>
                      </div>
                    </div>
                  )}
                </div>

                {/* Metadata Section */}
                <div className="pt-4">
                  <Button
                    variant="ghost"
                    className="w-full justify-between p-2 text-left font-normal hover:bg-gray-50 -ml-2"
                    onClick={() => setMetadataExpanded(!metadataExpanded)}
                  >
                    <span className="text-sm font-medium">Metadata</span>
                    {metadataExpanded ? (
                      <ChevronUp className="h-4 w-4" />
                    ) : (
                      <ChevronDown className="h-4 w-4" />
                    )}
                  </Button>
                  {metadataExpanded && (
                    <div className="mt-2">
                      <div className="bg-slate-50 border border-gray-200 rounded-md p-4">
                        {isItemDataLoading ? (
                          <div className="text-center py-4">
                            <Loader />
                            <p className="text-gray-500 mt-2">
                              Loading item data...
                            </p>
                          </div>
                        ) : (
                          renderValue(
                            currentItemData?.metadata || currentItem.metadata,
                          )
                        )}
                      </div>
                    </div>
                  )}
                </div>
              </div>

              {/* Vertical Separator */}
              <div className="border-l border-gray-200"></div>

              {/* Right Side - Feedback */}
              <div className="w-96">
                {/* Comments Section */}
                <div className="pb-4">
                  <h3 className="text-sm font-medium text-gray-900 mb-2">
                    Comments
                  </h3>
                  <Textarea
                    placeholder="Add a comment..."
                    value={comment}
                    onChange={(e) => setComment(e.target.value)}
                    className="min-h-16 resize-none"
                  />
                </div>

                {/* Horizontal Separator */}
                <div className="border-t border-gray-200 my-4"></div>

                {/* Feedback Scores Section */}
                <div className="pt-4">
                  <h3 className="text-sm font-medium text-gray-900 mb-4">
                    Feedback scores
                  </h3>

                  {/* Feedback Score Rows */}
                  <div className="border-t border-gray-200">
                    {queue.feedback_definitions.map((definitionId, index) => {
                      const definition =
                        feedbackDefinitionsMap.get(definitionId);
                      const colors = [
                        "#19A979",
                        "#5899DA",
                        "#BF399E",
                        "#F4B400",
                      ];
                      const currentScore = feedbackScores[definitionId];

                      if (!definition) {
                        return (
                          <div
                            key={definitionId}
                            className="border-b border-gray-200 last:border-b-0"
                          >
                            <div className="flex items-center p-2">
                              <div className="flex-1">
                                <div className="text-sm text-gray-500">
                                  Loading...
                                </div>
                              </div>
                            </div>
                          </div>
                        );
                      }

                      const isCategorical = definition.type === "categorical";
                      const categories = definition.details?.categories || {};
                      const categoryEntries = Object.entries(categories) as [
                        string,
                        number,
                      ][];
                      const isBinary =
                        isCategorical && categoryEntries.length === 2;

                      return (
                        <div
                          key={definitionId}
                          className="border-b border-gray-200 last:border-b-0"
                        >
                          <div className="flex items-center p-2">
                            {/* Score Tag */}
                            <div className="flex-1 min-w-0">
                              <FeedbackScoreTag
                                label={definition.name}
                                value={currentScore || 0}
                              />
                            </div>

                            {/* Input/Buttons */}
                            <div className="flex items-center space-x-2 ml-4">
                              {isBinary ? (
                                // Button options for binary scores
                                <div className="flex space-x-1">
                                  {categoryEntries.map(([label, value]) => (
                                    <Button
                                      key={value}
                                      variant="outline"
                                      size="sm"
                                      className={`h-7 px-2 text-xs ${
                                        currentScore === value
                                          ? "bg-blue-50 border-blue-300"
                                          : ""
                                      }`}
                                      onClick={() =>
                                        setFeedbackScores((prev) => ({
                                          ...prev,
                                          [definitionId]: value,
                                        }))
                                      }
                                    >
                                      {label} ({value})
                                    </Button>
                                  ))}
                                </div>
                              ) : (
                                // Input field for range scores
                                <Input
                                  type="number"
                                  min={definition.details?.min || 0}
                                  max={definition.details?.max || 5}
                                  step="0.1"
                                  placeholder={`Min: ${
                                    definition.details?.min || 0
                                  }, Max: ${definition.details?.max || 5}`}
                                  value={currentScore || ""}
                                  onChange={(e) =>
                                    setFeedbackScores((prev) => ({
                                      ...prev,
                                      [definitionId]:
                                        parseFloat(e.target.value) || 0,
                                    }))
                                  }
                                  className="w-32 h-7 text-xs"
                                />
                              )}

                              {/* Info button */}
                              <Button
                                variant="outline"
                                size="icon-sm"
                                className="h-7 w-7"
                                title={definition.description}
                              >
                                <Info className="h-3 w-3" />
                              </Button>
                            </div>
                          </div>
                        </div>
                      );
                    })}
                  </div>
                </div>
              </div>
            </div>
          </div>

          {/* Bottom Navigation */}
          <div className="flex justify-end items-center space-x-2 pt-4 border-t border-gray-200">
            <Button
              variant="outline"
              onClick={handlePrevious}
              disabled={currentItemIndex === 0}
            >
              <ChevronLeft className="mr-2 h-4 w-4" />
              Previous
            </Button>
            <Button
              variant="outline"
              onClick={handleSkip}
              disabled={currentItemIndex === items.length - 1}
            >
              Skip
              <ChevronRight className="ml-2 h-4 w-4" />
            </Button>
            <Button
              onClick={handleSubmitAndNext}
              disabled={
                mutation.isPending || Object.keys(feedbackScores).length === 0
              }
              className="bg-blue-600 hover:bg-blue-700"
            >
              {mutation.isPending ? "Saving..." : "Submit + Next"}
            </Button>
          </div>
        </div>
      </div>
    </div>
  );
};

export default SMEAnnotationPage;
