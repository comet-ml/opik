import React, { useState, useCallback } from "react";
import { useNavigate } from "@tanstack/react-router";
import { ChevronDown, ChevronUp, ChevronLeft, ChevronRight, Copy, Info, Table } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import FeedbackScoreTag from "@/components/shared/FeedbackScoreTag/FeedbackScoreTag";
import Loader from "@/components/shared/Loader/Loader";

import { SMEAnnotationQueue, AnnotationQueueScope } from "@/types/annotation-queues";
import useSMEQueueItems from "@/api/annotation-queues/useSMEQueueItems";
import useSMEProgress from "@/api/annotation-queues/useSMEProgress";
import useSMEAnnotationMutation from "@/api/annotation-queues/useSMEAnnotationMutation";
import { formatDate } from "@/lib/date";

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
  const [currentItemIndex, setCurrentItemIndex] = useState(0);
  const [inputExpanded, setInputExpanded] = useState(true);
  const [outputExpanded, setOutputExpanded] = useState(true);
  const [metadataExpanded, setMetadataExpanded] = useState(false);
  const [comment, setComment] = useState("");
  const [feedbackScores, setFeedbackScores] = useState<Record<string, number>>({});

  const { data: itemsData, isPending: isItemsLoading } = useSMEQueueItems({
    shareToken,
    page: 1,
    size: 1000, // Get all items for now - TODO: implement pagination for large queues
  });

  const { data: progress } = useSMEProgress({
    shareToken,
  });

  const items = itemsData?.content || [];
  const currentItem = items[currentItemIndex];


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
      feedback_scores: Object.entries(feedbackScores).map(([name, value]) => ({
        name,
        value,
      })),
      comment,
    };

    mutation.mutate(
      { shareToken, itemId: currentItem.id as string, annotation: submission },
      {
        onSuccess: () => {
          // Reset form and advance
          setComment("");
          setFeedbackScores({});
          handleNext();
        },
      }
    );
  }, [feedbackScores, comment, currentItem, shareToken, mutation, handleNext]);

  const progressData = progress ? {
    current: progress.completed_items,
    total: progress.total_items,
  } : {
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

  if (!currentItem) {
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
          <div className="flex items-center space-x-2">
            <div className="w-6 h-6 relative">
              {/* Opik logo circles */}
              <div className="absolute inset-0 bg-gradient-to-br from-orange-400 to-red-500 rounded-full opacity-80"></div>
              <div className="absolute top-1 left-1 w-1 h-1 bg-gradient-to-br from-orange-400 to-red-500 rounded-full"></div>
              <div className="absolute top-0.5 right-1 w-1.5 h-1.5 bg-gradient-to-br from-orange-400 to-red-500 rounded-full"></div>
            </div>
            <span className="text-lg font-medium text-gray-900">opik</span>
          </div>
        </div>
      </header>

      {/* Main Content */}
      <div className="bg-gray-50 min-h-screen px-8 py-10">
        <div className="max-w-6xl mx-auto space-y-10">
          {/* Header Section */}
          <div className="space-y-3">
            <div className="flex items-center justify-between">
              <h1 className="text-2xl font-medium text-gray-900">{queue.name}</h1>
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
                <span className="text-sm font-medium text-gray-700">Progress</span>
                <div className="bg-white border border-gray-200 rounded px-2 py-1 text-sm text-gray-600">
                  {progressData.current}/{progressData.total} completed
                </div>
              </div>
              <div className="w-full bg-gray-200 rounded-full h-1.5">
                <div 
                  className="bg-blue-600 h-1.5 rounded-full transition-all duration-300"
                  style={{ width: `${(progressData.current / progressData.total) * 100}%` }}
                />
              </div>
            </div>
          </div>

          {/* Content and Feedback */}
          <div className="flex gap-4">
            {/* Left Side - Content */}
            <div className="flex-1 bg-white border border-gray-200 rounded-md">
              {/* Input Section */}
              <div className="border-b border-gray-200">
                <Button
                  variant="ghost"
                  className="w-full justify-start p-4 text-left font-normal"
                  onClick={() => setInputExpanded(!inputExpanded)}
                >
                  <span className="text-sm font-medium">Input</span>
                </Button>
                {inputExpanded && (
                  <div className="border-t border-gray-200">
                    <div className="bg-gray-50 border-b border-gray-200 px-4 py-2 flex items-center justify-between">
                      <Button variant="outline" size="sm" className="text-xs">
                        Pretty ✨
                        <ChevronDown className="ml-1 h-3 w-3" />
                      </Button>
                      <Button variant="outline" size="icon-sm">
                        <Copy className="h-3 w-3" />
                      </Button>
                    </div>
                    <div className="p-4 bg-gray-50">
                      {renderValue(currentItem.input)}
                    </div>
                  </div>
                )}
              </div>

              {/* Output Section */}
              <div className="border-b border-gray-200">
                <Button
                  variant="ghost"
                  className="w-full justify-start p-4 text-left font-normal"
                  onClick={() => setOutputExpanded(!outputExpanded)}
                >
                  <span className="text-sm font-medium">Output</span>
                </Button>
                {outputExpanded && (
                  <div className="border-t border-gray-200">
                    <div className="bg-gray-50 border-b border-gray-200 px-4 py-2 flex items-center justify-between">
                      <Button variant="outline" size="sm" className="text-xs">
                        Pretty ✨
                        <ChevronDown className="ml-1 h-3 w-3" />
                      </Button>
                      <Button variant="outline" size="icon-sm">
                        <Copy className="h-3 w-3" />
                      </Button>
                    </div>
                    <div className="p-4 bg-gray-50">
                      {renderValue(currentItem.output)}
                    </div>
                  </div>
                )}
              </div>

              {/* Metadata Section */}
              <div>
                <Button
                  variant="ghost"
                  className="w-full justify-between p-4 text-left font-normal"
                  onClick={() => setMetadataExpanded(!metadataExpanded)}
                >
                  <span className="text-sm font-medium">Metadata</span>
                  {metadataExpanded ? <ChevronUp className="h-4 w-4" /> : <ChevronDown className="h-4 w-4" />}
                </Button>
                {metadataExpanded && (
                  <div className="border-t border-gray-200 p-4 bg-gray-50">
                    {renderValue(currentItem.metadata)}
                  </div>
                )}
              </div>
            </div>

            {/* Right Side - Feedback */}
            <div className="w-96">
              <div className="bg-white border border-gray-200 rounded-md p-6 space-y-4">
                {/* Comments Section */}
                <div>
                  <h3 className="text-sm font-medium text-gray-900 mb-2">Comments</h3>
                  <Textarea
                    placeholder="Add a comment..."
                    value={comment}
                    onChange={(e) => setComment(e.target.value)}
                    className="min-h-16 resize-none"
                  />
                </div>

                <div className="border-t border-gray-200 pt-4">
                  <h3 className="text-sm font-medium text-gray-900 mb-4">Feedback scores</h3>
                  
                  {/* Feedback Score Rows */}
                  <div className="space-y-1 border-t border-gray-200">
                    {queue.feedback_definitions.map((definition, index) => {
                      const colors = ['#19A979', '#5899DA', '#BF399E', '#F4B400'];
                      const color = colors[index % colors.length];
                      const currentScore = feedbackScores[definition];
                      
                      return (
                        <div key={definition} className="border-b border-gray-200 last:border-b-0">
                          <div className="flex items-center py-2">
                            {/* Score Tag */}
                            <div className="flex-1">
                              <FeedbackScoreTag
                                label={definition}
                                value={currentScore || 0}
                              />
                            </div>

                            {/* Input/Buttons */}
                            <div className="flex items-center space-x-2 ml-4">
                              {definition === 'has_errors' || definition === 'relevance' ? (
                                // Button options for binary scores
                                <div className="flex space-x-1">
                                  <Button
                                    variant="outline"
                                    size="sm"
                                    className={`h-7 px-2 text-xs ${currentScore === 0 ? 'bg-blue-50 border-blue-300' : ''}`}
                                    onClick={() => setFeedbackScores(prev => ({ ...prev, [definition]: 0 }))}
                                  >
                                    {definition === 'has_errors' ? 'No errors (0)' : 'Not relevant (0)'}
                                  </Button>
                                  <Button
                                    variant="outline"
                                    size="sm"
                                    className={`h-7 px-2 text-xs ${currentScore === 1 ? 'bg-blue-50 border-blue-300' : ''}`}
                                    onClick={() => setFeedbackScores(prev => ({ ...prev, [definition]: 1 }))}
                                  >
                                    {definition === 'has_errors' ? 'Has errors (1)' : 'Relevant (1)'}
                                  </Button>
                                </div>
                              ) : (
                                // Input field for range scores
                                <Input
                                  type="number"
                                  min="0"
                                  max="5"
                                  step="0.1"
                                  placeholder="Min: 0, Max: 5"
                                  value={currentScore || ''}
                                  onChange={(e) => setFeedbackScores(prev => ({ 
                                    ...prev, 
                                    [definition]: parseFloat(e.target.value) || 0 
                                  }))}
                                  className="w-32 h-7 text-xs"
                                />
                              )}
                              
                              {/* Info button */}
                              <Button variant="outline" size="icon-sm" className="h-7 w-7">
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
              disabled={mutation.isPending || Object.keys(feedbackScores).length === 0}
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
