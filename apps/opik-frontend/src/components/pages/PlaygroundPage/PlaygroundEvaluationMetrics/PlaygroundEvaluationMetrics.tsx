import React, { useState } from "react";
import { Play } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/components/ui/accordion";
import { Card, CardContent } from "@/components/ui/card";
import { Tag } from "@/components/ui/tag";
import { Separator } from "@/components/ui/separator";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

export interface EvaluationMetric {
  id: string;
  label: string;
  description: string;
  category: "heuristic" | "llm_judge";
  requiresReference?: boolean;
  requiresContext?: boolean;
}

const AVAILABLE_METRICS: EvaluationMetric[] = [
  {
    id: "equals",
    label: "Equals",
    description: "Checks for exact text match",
    category: "heuristic",
    requiresReference: true,
  },
  {
    id: "contains",
    label: "Contains",
    description: "Identifies presence of a substring",
    category: "heuristic",
    requiresReference: true,
  },
  {
    id: "isJson",
    label: "Is JSON",
    description: "Validates JSON format",
    category: "heuristic",
  },
  {
    id: "hallucination",
    label: "Hallucination",
    description: "Detects hallucinated content using LLM",
    category: "llm_judge",
    requiresContext: true,
  },
  {
    id: "answer_relevance",
    label: "Answer Relevance",
    description: "Evaluates answer relevance to the question",
    category: "llm_judge",
    requiresContext: true,
  },
  {
    id: "context_precision",
    label: "Context Precision",
    description: "Measures precision of retrieved context",
    category: "llm_judge",
    requiresContext: true,
  },
  {
    id: "context_recall",
    label: "Context Recall",
    description: "Measures recall of retrieved context",
    category: "llm_judge",
    requiresContext: true,
  },
];

interface PlaygroundEvaluationMetricsProps {
  selectedMetrics: string[];
  onMetricsChange: (metrics: string[]) => void;
  onRunEvaluation: () => void;
  isEvaluationRunning: boolean;
  hasDataset: boolean;
}

const PlaygroundEvaluationMetrics: React.FC<
  PlaygroundEvaluationMetricsProps
> = ({
  selectedMetrics,
  onMetricsChange,
  onRunEvaluation,
  isEvaluationRunning,
  hasDataset,
}) => {
  const [isOpen, setIsOpen] = useState(false);

  const handleMetricToggle = (metricId: string) => {
    const newMetrics = selectedMetrics.includes(metricId)
      ? selectedMetrics.filter((id) => id !== metricId)
      : [...selectedMetrics, metricId];
    onMetricsChange(newMetrics);
  };

  const heuristicMetrics = AVAILABLE_METRICS.filter(
    (m) => m.category === "heuristic",
  );
  const llmJudgeMetrics = AVAILABLE_METRICS.filter(
    (m) => m.category === "llm_judge",
  );

  const canRunEvaluation = hasDataset && selectedMetrics.length > 0;

  return (
    <Card className="w-full">
      <Accordion
        type="single"
        collapsible
        value={isOpen ? "item-1" : ""}
        onValueChange={(value) => setIsOpen(!!value)}
      >
        <AccordionItem value="item-1">
          <AccordionTrigger className="px-6 py-4 hover:no-underline">
            <div className="flex w-full items-center justify-between">
              <div className="flex items-center gap-2">
                <span className="text-sm font-medium">Evaluation Metrics</span>
                {selectedMetrics.length > 0 && (
                  <Tag variant="gray" size="sm">
                    {selectedMetrics.length}
                  </Tag>
                )}
              </div>
              {canRunEvaluation && (
                <Button
                  size="sm"
                  onClick={(e) => {
                    e.stopPropagation();
                    onRunEvaluation();
                  }}
                  disabled={isEvaluationRunning}
                  className="ml-2"
                >
                  <Play className="mr-1 size-4" />
                  {isEvaluationRunning ? "Running..." : "Run Evaluation"}
                </Button>
              )}
            </div>
          </AccordionTrigger>
          <AccordionContent>
            <CardContent className="pt-0">
              {!hasDataset && (
                <div className="mb-4 rounded-md bg-muted/50 p-4">
                  <p className="text-sm text-muted-foreground">
                    Select a dataset to enable evaluation metrics
                  </p>
                </div>
              )}

              <div className="space-y-4">
                {/* Heuristic Metrics */}
                <div>
                  <h4 className="mb-3 flex items-center gap-2 text-sm font-medium">
                    Heuristic Metrics
                    <Tag variant="green" size="sm">
                      Fast
                    </Tag>
                  </h4>
                  <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
                    {heuristicMetrics.map((metric) => (
                      <div
                        key={metric.id}
                        className="flex items-start space-x-2"
                      >
                        <Checkbox
                          id={metric.id}
                          checked={selectedMetrics.includes(metric.id)}
                          onCheckedChange={() => handleMetricToggle(metric.id)}
                          disabled={!hasDataset}
                        />
                        <div className="flex-1">
                          <TooltipWrapper content={metric.description}>
                            <label
                              htmlFor={metric.id}
                              className="cursor-pointer text-sm font-medium leading-none peer-disabled:cursor-not-allowed peer-disabled:opacity-70"
                            >
                              {metric.label}
                              {metric.requiresReference && (
                                <Tag variant="blue" size="sm" className="ml-1">
                                  Needs reference
                                </Tag>
                              )}
                            </label>
                          </TooltipWrapper>
                          <p className="mt-1 text-xs text-muted-foreground">
                            {metric.description}
                          </p>
                        </div>
                      </div>
                    ))}
                  </div>
                </div>

                <Separator />

                {/* LLM-as-Judge Metrics */}
                <div>
                  <h4 className="mb-3 flex items-center gap-2 text-sm font-medium">
                    LLM-as-Judge Metrics
                    <Tag variant="purple" size="sm">
                      AI-powered
                    </Tag>
                  </h4>
                  <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
                    {llmJudgeMetrics.map((metric) => (
                      <div
                        key={metric.id}
                        className="flex items-start space-x-2"
                      >
                        <Checkbox
                          id={metric.id}
                          checked={selectedMetrics.includes(metric.id)}
                          onCheckedChange={() => handleMetricToggle(metric.id)}
                          disabled={!hasDataset}
                        />
                        <div className="flex-1">
                          <TooltipWrapper content={metric.description}>
                            <label
                              htmlFor={metric.id}
                              className="cursor-pointer text-sm font-medium leading-none peer-disabled:cursor-not-allowed peer-disabled:opacity-70"
                            >
                              {metric.label}
                              {metric.requiresContext && (
                                <Tag variant="blue" size="sm" className="ml-1">
                                  Needs context
                                </Tag>
                              )}
                            </label>
                          </TooltipWrapper>
                          <p className="mt-1 text-xs text-muted-foreground">
                            {metric.description}
                          </p>
                        </div>
                      </div>
                    ))}
                  </div>
                </div>

                {selectedMetrics.length > 0 && hasDataset && (
                  <div className="pt-4">
                    <Button
                      onClick={onRunEvaluation}
                      disabled={isEvaluationRunning}
                      className="w-full"
                    >
                      <Play className="mr-2 size-4" />
                      {isEvaluationRunning
                        ? "Running Evaluation..."
                        : `Run Evaluation with ${selectedMetrics.length} Metrics`}
                    </Button>
                  </div>
                )}
              </div>
            </CardContent>
          </AccordionContent>
        </AccordionItem>
      </Accordion>
    </Card>
  );
};

export default PlaygroundEvaluationMetrics;
