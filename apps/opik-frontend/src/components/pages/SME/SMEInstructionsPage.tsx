import React from "react";
import { useNavigate } from "@tanstack/react-router";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Tag } from "@/components/ui/tag";
import { CheckCircle, FileText, MessageSquare, Star } from "lucide-react";

import { SMEAnnotationQueue, AnnotationQueueScope } from "@/types/annotation-queues";

type SMEInstructionsPageProps = {
  queue: SMEAnnotationQueue;
  shareToken: string;
};

const SMEInstructionsPage: React.FunctionComponent<SMEInstructionsPageProps> = ({
  queue,
  shareToken,
}) => {
  const navigate = useNavigate();

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
                  {queue.feedback_definitions.map((definition, index) => (
                    <div key={definition} className="grid grid-cols-3 py-3 px-2 border-b border-gray-200 last:border-b-0">
                      <div className="px-3 flex items-center space-x-2">
                        <div 
                          className="w-2 h-2 rounded-sm"
                          style={{ 
                            backgroundColor: index === 0 ? '#19A979' : index === 1 ? '#5899DA' : index === 2 ? '#BF399E' : '#F4B400'
                          }}
                        />
                        <span className="text-sm font-medium text-gray-600">{definition}</span>
                      </div>
                      <div className="px-3">
                        <p className="text-sm text-gray-700">
                          {definition === 'accuracy' && 'Measures whether the model\'s output is factually correct and matches the expected answer.'}
                          {definition === 'has_errors' && 'Use this score if the AI\'s response contains factual mistakes, incorrect calculations, or misleading information.'}
                          {definition === 'relevance' && 'Measures how well the response stays on topic and addresses the input.'}
                          {definition === 'simplicity' && 'Evaluates if the output is easy to read and understand.'}
                          {!['accuracy', 'has_errors', 'relevance', 'simplicity'].includes(definition) && `Evaluate the ${definition} of the response.`}
                        </p>
                      </div>
                      <div className="px-3">
                        <span className="text-sm text-gray-700">
                          {definition === 'has_errors' ? 'No errors (0), Has errors (1)' : 
                           definition === 'relevance' ? 'Not relevant (0), Relevant (1)' : 
                           'Min: 0, Max: 5'}
                        </span>
                      </div>
                    </div>
                  ))}
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
