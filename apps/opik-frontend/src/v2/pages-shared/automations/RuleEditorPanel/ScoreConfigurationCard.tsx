import React, { useCallback } from "react";
import { UseFormReturn } from "react-hook-form";
import { BrainCircuit, Plus } from "lucide-react";
import get from "lodash/get";

import { Button } from "@/ui/button";
import { FormErrorSkeleton } from "@/ui/form";
import { LLM_SCHEMA_TYPE, LLMJudgeSchema } from "@/types/llm";
import { EvaluationRuleFormType } from "@/v2/pages-shared/automations/RuleEditorPanel/schema";
import RuleSettingsCard from "@/v2/pages-shared/automations/RuleEditorPanel/RuleSettingsCard";
import ScoreBlock from "@/v2/pages-shared/automations/RuleEditorPanel/ScoreBlock";
import ScoreTypeSelect from "@/v2/pages-shared/automations/RuleEditorPanel/ScoreTypeSelect";
import AdditionalScore from "@/v2/pages-shared/automations/RuleEditorPanel/AdditionalScore";

const NEW_SCORE: LLMJudgeSchema = {
  name: "",
  type: LLM_SCHEMA_TYPE.BOOLEAN,
  description: "",
  unsaved: false,
};

type ScoreConfigurationCardProps = {
  form: UseFormReturn<EvaluationRuleFormType>;
};

const ScoreConfigurationCard: React.FC<ScoreConfigurationCardProps> = ({
  form,
}) => {
  const scores: LLMJudgeSchema[] = form.watch("llmJudgeDetails.schema") ?? [];
  const [mainScore, ...additionalScores] = scores;

  const setScores = useCallback(
    (next: LLMJudgeSchema[]) =>
      form.setValue("llmJudgeDetails.schema", next, { shouldDirty: true }),
    [form],
  );

  const scoresError: string | undefined =
    get(form.formState.errors, ["llmJudgeDetails", "schema", "message"]) ??
    get(form.formState.errors, [
      "llmJudgeDetails",
      "schema",
      "root",
      "message",
    ]);

  const updateScore = (index: number, changes: Partial<LLMJudgeSchema>) =>
    setScores(
      scores.map((score, i) =>
        i === index ? { ...score, ...changes } : score,
      ),
    );

  return (
    <RuleSettingsCard
      icon={BrainCircuit}
      iconClassName="bg-[var(--rule-icon-llm-judge)]"
      title="Score configuration"
      description="Create multiple scores for one evaluator. Define each score so the evaluator knows how to compute it."
    >
      <div className="flex flex-col gap-1.5 p-2">
        {mainScore && (
          <ScoreBlock title="Main score">
            <ScoreTypeSelect
              value={mainScore.type}
              onChange={(type) => updateScore(0, { type })}
              className="h-8 px-2"
            />
          </ScoreBlock>
        )}
        {additionalScores.map((score, index) => {
          const scoreIndex = index + 1;
          return (
            <AdditionalScore
              key={scoreIndex}
              title={`Score ${scoreIndex + 1}`}
              score={score}
              nameError={get(form.formState.errors, [
                "llmJudgeDetails",
                "schema",
                scoreIndex,
                "name",
                "message",
              ])}
              onChange={(changes) => updateScore(scoreIndex, changes)}
              onRemove={() =>
                setScores(scores.filter((_, i) => i !== scoreIndex))
              }
            />
          );
        })}
        {scoresError && <FormErrorSkeleton>{scoresError}</FormErrorSkeleton>}
        <div className="flex h-8 w-full items-center justify-center rounded-md border border-dashed border-border bg-soft-background">
          <Button
            type="button"
            variant="ghost"
            size="xs"
            className="text-foreground hover:text-primary-hover"
            onClick={() => setScores([...scores, { ...NEW_SCORE }])}
          >
            <Plus className="mr-0.5 size-3" />
            Additional score
          </Button>
        </div>
      </div>
    </RuleSettingsCard>
  );
};

export default ScoreConfigurationCard;
