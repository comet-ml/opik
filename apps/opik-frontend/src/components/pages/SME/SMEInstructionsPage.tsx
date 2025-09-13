import React, { useMemo } from "react";
import { useNavigate } from "@tanstack/react-router";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Tag } from "@/components/ui/tag";
import { CheckCircle, FileText, MessageSquare, Star } from "lucide-react";

import { SMEAnnotationQueue, AnnotationQueueScope } from "@/types/annotation-queues";
import useFeedbackDefinitionsList from "@/api/feedback-definitions/useFeedbackDefinitionsList";
import useAppStore from "@/store/AppStore";
import imageLogoUrl from "/images/opik-logo.png";

type SMEInstructionsPageProps = {
  queue: SMEAnnotationQueue;
  shareToken: string;
};

const SMEInstructionsPage: React.FunctionComponent<SMEInstructionsPageProps> = ({
  queue,
  shareToken,
}) => {
  const navigate = useNavigate();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

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
    feedbackDefinitions.forEach(def => {
      map.set(def.id, def);
    });
    return map;
  }, [feedbackDefinitions]);

  const handleStartAnnotating = () => {
    navigate({
      to: `/sme/queue/${shareToken}`,
      search: { view: "annotate" },
    });
  };

  return (
    <div className="min-h-screen bg-white">
      {/* Header with Opik Logo */}
      <header className="bg-white border-b border-gray-200 px-4 py-3">
        <div className="flex items-center">
          {/* Opik Logo */}
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
      <div className="bg-gray-50 min-h-screen">
        <div className="max-w-4xl mx-auto px-8 py-10">
          {/* Get Started Content */}
          <div className="space-y-10">
            {/* Header */}
            <div className="text-center space-y-3">
              <h1 className="text-2xl font-medium text-gray-900">Get started with Opik</h1>
              <p className="text-sm text-gray-600">
                Welcome! You've been invited to review examples of AI responses in this queue and share your feedback.
              </p>
            </div>

            {/* Instructions Section */}
            <div className="space-y-2">
              <h2 className="text-lg font-medium text-gray-900">Instructions</h2>
              <div className="bg-white border border-gray-200 rounded-md p-5">
                <div className="text-sm text-gray-700 leading-relaxed">
                  {queue.instructions || (
                    <>
                      <p className="mb-4">Please review the AI's answers to financial questions and check that they are:</p>
                      <div className="space-y-2 mb-4">
                        <p><strong>Accurate</strong> – Facts, numbers, and calculations should be correct.</p>
                        <p><strong>Relevant</strong> – The response should address the user's question and provide useful guidance.</p>
                        <p><strong>Simple</strong> – The explanation should be easy to understand.</p>
                      </div>
                      <p>If the response contains errors or is confusing, mark it accordingly and leave a short comment.</p>
                    </>
                  )}
                </div>
              </div>
            </div>

            {/* Feedback Options Section */}
            <div className="space-y-2">
              <div className="space-y-1">
                <h2 className="text-lg font-medium text-gray-900">Feedback options</h2>
                <p className="text-sm text-gray-600">
                  Here are the types of feedback you can give when reviewing responses, along with the possible values for each.
                </p>
              </div>

              {/* Feedback Table */}
              <div className="border border-gray-200 rounded-md overflow-hidden">
                {/* Table Header */}
                <div className="bg-gray-50 border-b border-gray-200 px-2">
                  <div className="grid grid-cols-3 py-3">
                    <div className="px-3 text-sm font-medium text-gray-600">Feedback score name</div>
                    <div className="px-3 text-sm font-medium text-gray-600">Description</div>
                    <div className="px-3 text-sm font-medium text-gray-600">Available values</div>
                  </div>
                </div>

                {/* Table Rows */}
                <div className="bg-white">
                  {queue.feedback_definitions.map((definitionId, index) => {
                    const definition = feedbackDefinitionsMap.get(definitionId);
                    const colors = ['#19A979', '#5899DA', '#BF399E', '#F4B400'];
                    
                    if (!definition) {
                      return (
                        <div key={definitionId} className="grid grid-cols-3 py-3 px-2 border-b border-gray-200 last:border-b-0">
                          <div className="px-3 flex items-center space-x-2">
                            <div 
                              className="w-2 h-2 rounded-sm"
                              style={{ backgroundColor: colors[index % colors.length] }}
                            />
                            <span className="text-sm font-medium text-gray-600">Loading...</span>
                          </div>
                          <div className="px-3">
                            <p className="text-sm text-gray-700">Loading definition...</p>
                          </div>
                          <div className="px-3">
                            <span className="text-sm text-gray-700">Loading...</span>
                          </div>
                        </div>
                      );
                    }

                    const getAvailableValues = (def: any) => {
                      if (def.type === 'categorical') {
                        const categories = def.details?.categories || {};
                        const categoryEntries = Object.entries(categories);
                        if (categoryEntries.length === 0) {
                          return 'No values defined';
                        }
                        return categoryEntries
                          .map(([label, value]) => `${label} (${value})`)
                          .join(', ');
                      } else if (def.type === 'numerical') {
                        const min = def.details?.min || 0;
                        const max = def.details?.max || 5;
                        return `Min: ${min}, Max: ${max}`;
                      }
                      return 'No values defined';
                    };

                    return (
                      <div key={definitionId} className="grid grid-cols-3 py-3 px-2 border-b border-gray-200 last:border-b-0">
                        <div className="px-3 flex items-center space-x-2">
                          <div 
                            className="w-2 h-2 rounded-sm"
                            style={{ backgroundColor: colors[index % colors.length] }}
                          />
                          <span className="text-sm font-medium text-gray-600">{definition.name}</span>
                        </div>
                        <div className="px-3">
                          <p className="text-sm text-gray-700">
                            {definition.description || `Evaluate the ${definition.name} of the response.`}
                          </p>
                        </div>
                        <div className="px-3">
                          <span className="text-sm text-gray-700">
                            {getAvailableValues(definition)}
                          </span>
                        </div>
                      </div>
                    );
                  })}
                </div>
              </div>
            </div>
          </div>

          {/* Bottom Button */}
          <div className="flex justify-end pt-4 border-t border-gray-200 mt-8">
            <Button 
              onClick={handleStartAnnotating}
              className="bg-blue-600 hover:bg-blue-700 text-white px-4 py-2.5 h-10 font-medium"
            >
              Start annotating
            </Button>
          </div>
        </div>
      </div>
    </div>
  );
};

export default SMEInstructionsPage;
