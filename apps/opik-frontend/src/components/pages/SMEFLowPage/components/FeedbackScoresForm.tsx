import React from "react";
import { Card } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";

interface FeedbackScore {
  name: string;
  value: number | null;
  type: "range" | "binary" | "categorical";
  min?: number;
  max?: number;
  options?: Array<{ label: string; value: number }>;
  description?: string;
}

interface FeedbackScoresFormProps {
  feedbackScores: FeedbackScore[];
  onScoreChange: (scoreIndex: number, value: number) => void;
  className?: string;
}

const FeedbackScoresForm: React.FunctionComponent<FeedbackScoresFormProps> = ({
  feedbackScores,
  onScoreChange,
  className = "",
}) => {
  return (
    <Card className={`p-4 ${className}`}>
      <h3 className="comet-body-s mb-4 font-medium">Feedback scores</h3>
      <div className="space-y-4">
        {feedbackScores.map((score, index) => (
          <div key={score.name} className="space-y-2">
            <div className="flex items-center space-x-2">
              <div
                className={`size-3 rounded-full ${
                  index % 4 === 0
                    ? "bg-green-500"
                    : index % 4 === 1
                      ? "bg-blue-500"
                      : index % 4 === 2
                        ? "bg-purple-500"
                        : "bg-orange-500"
                }`}
              />
              <div className="flex-1">
                <span className="comet-body-xs font-medium">{score.name}</span>
                {score.description && (
                  <p className="comet-body-xs text-gray-500">
                    {score.description}
                  </p>
                )}
              </div>
            </div>

            {score.type === "range" && (
              <div className="flex items-center space-x-2">
                <span className="comet-body-xs text-gray-500">
                  Min: {score.min}
                </span>
                <Input
                  type="number"
                  min={score.min}
                  max={score.max}
                  value={score.value || ""}
                  onChange={(e) => onScoreChange(index, Number(e.target.value))}
                  className="h-8 w-16 text-center"
                />
                <span className="comet-body-xs text-gray-500">
                  Max: {score.max}
                </span>
              </div>
            )}

            {(score.type === "binary" || score.type === "categorical") &&
              score.options && (
                <div
                  className={`flex ${
                    score.type === "binary" ? "space-x-2" : "flex-wrap gap-2"
                  }`}
                >
                  {score.options.map((option) => (
                    <Button
                      key={option.value}
                      variant={
                        score.value === option.value ? "default" : "outline"
                      }
                      size="sm"
                      onClick={() => onScoreChange(index, option.value)}
                      className={`${
                        score.type === "binary" ? "flex-1" : ""
                      } text-xs`}
                    >
                      {option.label}
                    </Button>
                  ))}
                </div>
              )}
          </div>
        ))}
      </div>
    </Card>
  );
};

export default FeedbackScoresForm;
