import React from "react";
import { Play, Plus, ChevronDown } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
  DropdownMenuSeparator,
  DropdownMenuLabel,
} from "@/components/ui/dropdown-menu";
import { Tag } from "@/components/ui/tag";
import LoadableSelectBox from "@/components/shared/LoadableSelectBox/LoadableSelectBox";
import useDatasetsList from "@/api/datasets/useDatasetsList";
import { DropdownOption } from "@/types/shared";

// Available evaluators matching LangSmith style
const EVALUATORS = [
  {
    id: "equals",
    label: "Exact Match",
    description: "Check exact match with reference",
  },
  {
    id: "contains",
    label: "Contains",
    description: "Check if output contains expected text",
  },
  {
    id: "hallucination",
    label: "Hallucination",
    description: "Detect hallucinated content",
  },
  {
    id: "answer_relevance",
    label: "Answer Relevance",
    description: "Evaluate answer relevance",
  },
  {
    id: "context_precision",
    label: "Context Precision",
    description: "Measure context precision",
  },
  {
    id: "context_recall",
    label: "Context Recall",
    description: "Measure context recall",
  },
];

interface PlaygroundEvaluationBarProps {
  datasetId: string | null;
  onDatasetChange: (datasetId: string | null) => void;
  selectedEvaluators: string[];
  onEvaluatorsChange: (evaluators: string[]) => void;
  onStartExperiment: () => void;
  isRunning: boolean;
  workspaceName: string;
}

const PlaygroundEvaluationBar: React.FC<PlaygroundEvaluationBarProps> = ({
  datasetId,
  onDatasetChange,
  selectedEvaluators,
  onEvaluatorsChange,
  onStartExperiment,
  isRunning,
  workspaceName,
}) => {
  const { data: datasetsData } = useDatasetsList(
    { workspaceName, page: 1, size: 100 },
    { enabled: !!workspaceName },
  );

  const datasetOptions: DropdownOption<string>[] =
    datasetsData?.content?.map((dataset) => ({
      value: dataset.id,
      label: dataset.name,
    })) || [];

  const handleAddEvaluator = (evaluatorId: string) => {
    if (!selectedEvaluators.includes(evaluatorId)) {
      onEvaluatorsChange([...selectedEvaluators, evaluatorId]);
    }
  };

  const handleRemoveEvaluator = (evaluatorId: string) => {
    onEvaluatorsChange(selectedEvaluators.filter((id) => id !== evaluatorId));
  };

  const canStartExperiment = datasetId && selectedEvaluators.length > 0;

  return (
    <div className="border-t bg-muted/30 px-6 py-3">
      <div className="flex items-center gap-4">
        {/* Dataset Selection */}
        <div className="flex items-center gap-2">
          <span className="text-sm font-medium text-muted-foreground">
            Dataset:
          </span>
          <div className="min-w-[200px]">
            <LoadableSelectBox
              placeholder="Select dataset..."
              value={datasetId || undefined}
              onChange={(value) => onDatasetChange(value || null)}
              options={datasetOptions}
            />
          </div>
        </div>

        {/* Evaluators */}
        <div className="flex items-center gap-2">
          <div className="flex items-center gap-1">
            {selectedEvaluators.map((evalId) => {
              const evaluator = EVALUATORS.find((e) => e.id === evalId);
              return (
                <Tag
                  key={evalId}
                  variant="gray"
                  className="cursor-pointer hover:bg-destructive hover:text-destructive-foreground transition-colors"
                  onClick={() => handleRemoveEvaluator(evalId)}
                >
                  {evaluator?.label} ×
                </Tag>
              );
            })}
          </div>

          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button variant="outline" size="sm">
                <Plus className="h-4 w-4 mr-1" />
                Evaluator
                <ChevronDown className="h-4 w-4 ml-1" />
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="start" className="w-64">
              <DropdownMenuLabel>Add Evaluator</DropdownMenuLabel>
              <DropdownMenuSeparator />
              {EVALUATORS.map((evaluator) => (
                <DropdownMenuItem
                  key={evaluator.id}
                  onClick={() => handleAddEvaluator(evaluator.id)}
                  disabled={selectedEvaluators.includes(evaluator.id)}
                  className="flex flex-col items-start gap-1 py-2"
                >
                  <div className="font-medium">{evaluator.label}</div>
                  <div className="text-xs text-muted-foreground">
                    {evaluator.description}
                  </div>
                </DropdownMenuItem>
              ))}
            </DropdownMenuContent>
          </DropdownMenu>
        </div>

        {/* Start Button */}
        <div className="ml-auto">
          <Button
            onClick={onStartExperiment}
            disabled={!canStartExperiment || isRunning}
            className="min-w-[100px]"
          >
            <Play className="h-4 w-4 mr-2" />
            {isRunning ? "Running..." : "Start"}
          </Button>
        </div>
      </div>

      {/* Help text when no dataset selected */}
      {!datasetId && (
        <div className="mt-2 text-xs text-muted-foreground">
          Select a dataset to start testing your prompt across multiple examples
        </div>
      )}
    </div>
  );
};

export default PlaygroundEvaluationBar;
