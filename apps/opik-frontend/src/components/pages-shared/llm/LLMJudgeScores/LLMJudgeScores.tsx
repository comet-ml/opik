import React, { useCallback } from "react";
import { Plus } from "lucide-react";
import get from "lodash/get";

import { LLMJudgeSchema } from "@/types/llm";
import { LLM_SCHEMA_TYPE, ScoresValidationError } from "@/types/llm";
import { Button } from "@/components/ui/button";
import { FormErrorSkeleton } from "@/components/ui/form";
import LLMJudgeScore from "@/components/pages-shared/llm/LLMJudgeScores/LLMJudgeScore";

interface LLMJudgeScoresProps {
  validationErrors?: ScoresValidationError;
  scores: LLMJudgeSchema[];
  onChange: (scores: LLMJudgeSchema[]) => void;
}

const LLMJudgeScores = ({
  validationErrors,
  scores,
  onChange,
}: LLMJudgeScoresProps) => {
  const generalError = get(validationErrors, "message");
  const handleAddScore = useCallback(() => {
    onChange([
      ...scores,
      {
        name: "Score name",
        description: "Score description",
        type: LLM_SCHEMA_TYPE.INTEGER,
        unsaved: false,
      },
    ]);
  }, [onChange, scores]);

  const handleRemoveScore = useCallback(
    (index: number) => {
      onChange(scores.filter((s, i) => i !== index));
    },
    [onChange, scores],
  );

  const handleChangeScore = useCallback(
    (index: number, changes: Partial<LLMJudgeSchema>) => {
      onChange(scores.map((s, i) => (i !== index ? s : { ...s, ...changes })));
    },
    [onChange, scores],
  );

  return (
    <div className="overflow-y-auto">
      <div className="flex flex-col gap-2 overflow-hidden">
        {scores.map((score, index) => (
          <LLMJudgeScore
            key={score.name + index}
            hideRemoveButton={scores?.length === 1}
            error={get(validationErrors, [index])}
            onRemoveScore={() => handleRemoveScore(index)}
            onChangeScore={(changes) => handleChangeScore(index, changes)}
            score={score}
          />
        ))}
      </div>
      {generalError && (
        <FormErrorSkeleton className="mt-2">{generalError}</FormErrorSkeleton>
      )}

      <Button
        variant="outline"
        size="sm"
        className="mt-2"
        onClick={handleAddScore}
        type="button"
      >
        <Plus className="mr-2 size-4" />
        Add score
      </Button>
    </div>
  );
};

export default LLMJudgeScores;
