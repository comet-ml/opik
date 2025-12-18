import React from "react";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Label } from "@/components/ui/label";
import { Card, CardContent } from "@/components/ui/card";
import { OptimizationStudio } from "@/types/optimizations";
import { LLM_MESSAGE_ROLE_NAME_MAP } from "@/constants/llm";
import { LLM_MESSAGE_ROLE } from "@/types/llm";
import SyntaxHighlighter from "@/components/shared/SyntaxHighlighter/SyntaxHighlighter";

interface OptimizationConfigModalProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  optimization: OptimizationStudio | null;
}

const OptimizationConfigModal: React.FC<OptimizationConfigModalProps> = ({
  open,
  onOpenChange,
  optimization,
}) => {
  if (!optimization?.studio_config) {
    return null;
  }

  const { studio_config } = optimization;
  const { prompt, llm_model, evaluation, optimizer } = studio_config;

  const getAlgorithmLabel = (type: string) => {
    switch (type) {
      case "gepa":
        return "GEPA optimizer";
      case "hierarchical_reflective":
        return "Hierarchical Reflective";
      case "evolutionary":
        return "Evolutionary";
      default:
        return type;
    }
  };

  const getMetricLabel = (type: string) => {
    switch (type) {
      case "equals":
        return "Equals";
      case "json_schema_validator":
        return "JSON Schema Validator";
      case "geval":
        return "Custom (G-Eval)";
      case "levenshtein_ratio":
        return "Levenshtein";
      default:
        return type;
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[90vh] max-w-2xl overflow-y-auto">
        <DialogHeader>
          <DialogTitle>Optimization Configuration</DialogTitle>
        </DialogHeader>

        <div className="flex flex-col gap-6">
          {/* Model & Settings */}
          <div className="flex gap-4">
            <div className="flex-1">
              <Label className="mb-2 block text-muted-slate">Model</Label>
              <div className="comet-body-s">{llm_model.model}</div>
            </div>
            {llm_model.parameters?.temperature !== undefined && (
              <div className="flex-1">
                <Label className="mb-2 block text-muted-slate">
                  Temperature
                </Label>
                <div className="comet-body-s">
                  {String(llm_model.parameters.temperature)}
                </div>
              </div>
            )}
          </div>

          {/* Metric */}
          <div>
            <Label className="mb-2 block text-muted-slate">Metric</Label>
            <div className="comet-body-s">
              {getMetricLabel(evaluation.metrics[0]?.type || "")}
            </div>
            {evaluation.metrics[0]?.parameters &&
              Object.keys(evaluation.metrics[0].parameters).length > 0 && (
                <div className="mt-2">
                  <SyntaxHighlighter data={evaluation.metrics[0].parameters} />
                </div>
              )}
          </div>

          {/* Algorithm */}
          <div>
            <Label className="mb-2 block text-muted-slate">Algorithm</Label>
            <div className="comet-body-s">
              {getAlgorithmLabel(optimizer.type)}
            </div>
            {optimizer.parameters &&
              Object.keys(optimizer.parameters).length > 0 && (
                <div className="mt-2">
                  <SyntaxHighlighter data={optimizer.parameters} />
                </div>
              )}
          </div>

          {/* Dataset */}
          <div>
            <Label className="mb-2 block text-muted-slate">Dataset</Label>
            <div className="comet-body-s">{optimization.dataset_name}</div>
          </div>

          {/* Prompt Messages */}
          <div>
            <Label className="mb-2 block text-muted-slate">Prompt</Label>
            <div className="flex flex-col gap-3">
              {prompt.messages.map((message, index) => (
                <Card key={index}>
                  <CardContent className="p-4">
                    <div className="mb-2 text-xs font-medium text-muted-slate">
                      {LLM_MESSAGE_ROLE_NAME_MAP[
                        message.role as LLM_MESSAGE_ROLE
                      ] || message.role}
                    </div>
                    <div className="comet-body-s whitespace-pre-wrap">
                      {message.content}
                    </div>
                  </CardContent>
                </Card>
              ))}
            </div>
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
};

export default OptimizationConfigModal;
