import React, { useCallback } from "react";
import { Plus, SlidersHorizontal } from "lucide-react";
import get from "lodash/get";

import { LLMJudgeSchema } from "@/types/llm";
import { LLM_SCHEMA_TYPE, ScoresValidationError } from "@/types/llm";
import { Button } from "@/ui/button";
import { FormErrorSkeleton } from "@/ui/form";
import { Tag } from "@/ui/tag";
import LLMJudgeScore from "@/v2/pages-shared/llm/LLMJudgeScores/LLMJudgeScore";

interface LLMJudgeScoresProps {
  validationErrors?: ScoresValidationError;
  scores: LLMJudgeSchema[];
  onChange: (scores: LLMJudgeSchema[]) => void;
  /** Main score normally mirrors the rule name; show its name field when it doesn't. */
  showMainScoreName?: boolean;
}

/**
 * Score configuration card: one main score plus optional additional scores.
 * The backend turns these into the judge's structured-output schema, so the
 * prompt never has to describe the output format.
 */
const LLMJudgeScores = ({
  validationErrors,
  scores,
  onChange,
  showMainScoreName = false,
}: LLMJudgeScoresProps) => {
  const generalError = get(validationErrors, "message");

  const handleAddScore = useCallback(() => {
    onChange([
      ...scores,
      {
        name: "",
        description: "",
        type: LLM_SCHEMA_TYPE.BOOLEAN,
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
    <div
      className="overflow-hidden rounded-md border border-border"
      data-testid="llm-judge-score-configuration"
    >
      <div className="flex flex-col gap-1 border-b border-border bg-soft-background p-3">
        <div className="comet-body-s-accented flex items-center gap-2">
          <Tag variant="turquoise" size="sm" className="px-1">
            <SlidersHorizontal className="size-3" />
          </Tag>
          Score configuration
        </div>
        <span className="comet-body-s text-light-slate">
          Create multiple scores for one rule. Define each score so the judge
          knows how to compute it.
        </span>
      </div>
      <div className="flex flex-col gap-2 p-2">
        {scores.map((score, index) => (
          <LLMJudgeScore
            key={index}
            title={index === 0 ? "Main score" : `Score ${index + 1}`}
            showName={index > 0 || showMainScoreName}
            showDescription={index > 0}
            error={get(validationErrors, [index])}
            onRemoveScore={
              index > 0 ? () => handleRemoveScore(index) : undefined
            }
            onChangeScore={(changes) => handleChangeScore(index, changes)}
            score={score}
          />
        ))}
        {generalError && <FormErrorSkeleton>{generalError}</FormErrorSkeleton>}
        <Button
          variant="outline"
          size="sm"
          className="w-full border-dashed"
          onClick={handleAddScore}
          type="button"
        >
          <Plus className="mr-1 size-3.5" />
          Additional score
        </Button>
      </div>
    </div>
  );
};

export default LLMJudgeScores;
