import React from "react";
import { CheckCircle, ExternalLink, RotateCw, TrendingUp } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Tag } from "@/components/ui/tag";
import { Separator } from "@/components/ui/separator";
import {
  PlaygroundEvaluationResponse,
  MetricSummary,
} from "@/api/playground/usePlaygroundEvaluation";

interface PlaygroundEvaluationResultsProps {
  results: PlaygroundEvaluationResponse;
  onViewFullAnalysis: () => void;
  onRunAnother: () => void;
}

const PlaygroundEvaluationResults: React.FC<
  PlaygroundEvaluationResultsProps
> = ({ results, onViewFullAnalysis, onRunAnother }) => {
  const formatScore = (score: number): string => {
    return (score * 100).toFixed(1);
  };

  const formatDuration = (ms: number): string => {
    if (ms < 1000) return `${ms}ms`;
    return `${(ms / 1000).toFixed(1)}s`;
  };

  const getPassRate = (summary: MetricSummary): number => {
    if (summary.passCount === undefined) return 0;
    return (summary.passCount / summary.totalCount) * 100;
  };

  return (
    <div className="mt-6 w-full space-y-4">
      {/* Success Banner */}
      <Card className="border-green-200 bg-green-50">
        <CardHeader className="pb-3">
          <CardTitle className="flex items-center gap-2 text-sm font-medium">
            <CheckCircle className="size-5 text-green-600" />
            <span className="text-green-800">Evaluation Complete!</span>
            <div className="ml-auto flex items-center gap-2">
              <Button
                variant="outline"
                size="sm"
                onClick={onViewFullAnalysis}
                className="border-green-300 hover:bg-green-100"
              >
                <TrendingUp className="mr-1 size-4" />
                View Full Analysis
              </Button>
              <Button
                variant="outline"
                size="sm"
                onClick={onRunAnother}
                className="border-green-300 hover:bg-green-100"
              >
                <RotateCw className="mr-1 size-4" />
                Run Another
              </Button>
            </div>
          </CardTitle>
        </CardHeader>
        <CardContent className="pt-0">
          <div className="flex items-center gap-6 text-sm">
            <div className="text-green-700">
              <span className="font-medium">Experiment:</span>{" "}
              {results.experimentName}
            </div>
            <div className="text-green-700">
              <span className="font-medium">Items:</span> {results.totalItems}
            </div>
            <div className="text-green-700">
              <span className="font-medium">Duration:</span>{" "}
              {formatDuration(results.durationMs)}
            </div>
          </div>
        </CardContent>
      </Card>

      {/* Metrics Summary */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2 text-base">
            <TrendingUp className="size-5" />
            Metrics Summary
          </CardTitle>
        </CardHeader>
        <CardContent>
          <div className="grid grid-cols-1 gap-4 md:grid-cols-2 lg:grid-cols-3">
            {Object.entries(results.metricsSummary).map(
              ([metricName, summary]) => (
                <div key={metricName} className="space-y-3">
                  <div className="flex items-center justify-between">
                    <h4 className="text-sm font-medium capitalize">
                      {metricName.replace("_", " ")}
                    </h4>
                    {summary.averageScore !== undefined && (
                      <Tag variant="gray">
                        {formatScore(summary.averageScore)}%
                      </Tag>
                    )}
                  </div>

                  {summary.averageScore !== undefined && (
                    <div className="space-y-1">
                      <div className="h-2 w-full rounded-full bg-gray-200">
                        <div
                          className="h-2 rounded-full bg-blue-600"
                          style={{ width: `${summary.averageScore * 100}%` }}
                        ></div>
                      </div>
                      <div className="flex justify-between text-xs text-muted-foreground">
                        <span>Score: {formatScore(summary.averageScore)}%</span>
                        <span>{summary.totalCount} items</span>
                      </div>
                    </div>
                  )}

                  {summary.passCount !== undefined && (
                    <div className="space-y-1">
                      <div className="h-2 w-full rounded-full bg-gray-200">
                        <div
                          className="h-2 rounded-full bg-green-600"
                          style={{ width: `${getPassRate(summary)}%` }}
                        ></div>
                      </div>
                      <div className="flex justify-between text-xs text-muted-foreground">
                        <span>
                          Pass rate: {getPassRate(summary).toFixed(1)}%
                        </span>
                        <span>
                          {summary.passCount}/{summary.totalCount}
                        </span>
                      </div>
                    </div>
                  )}
                </div>
              ),
            )}
          </div>
        </CardContent>
      </Card>

      {/* Quick Actions */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base">Next Steps</CardTitle>
        </CardHeader>
        <CardContent>
          <div className="flex flex-col gap-3 sm:flex-row">
            <Button onClick={onViewFullAnalysis} className="flex-1">
              <ExternalLink className="mr-2 size-4" />
              View Complete Experiment
            </Button>
            <Button variant="outline" onClick={onRunAnother} className="flex-1">
              <RotateCw className="mr-2 size-4" />
              Run Another Evaluation
            </Button>
          </div>
          <Separator className="my-4" />
          <div className="text-sm text-muted-foreground">
            <p>
              Your evaluation results have been saved as experiment{" "}
              <strong>{results.experimentName}</strong>. View the complete
              analysis to see individual item results, compare with previous
              experiments, and export your data.
            </p>
          </div>
        </CardContent>
      </Card>
    </div>
  );
};

export default PlaygroundEvaluationResults;
