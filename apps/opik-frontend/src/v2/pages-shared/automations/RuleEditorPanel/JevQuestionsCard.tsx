import React, { useCallback } from "react";
import { UseFormReturn } from "react-hook-form";
import { CircleHelp, Plus } from "lucide-react";
import get from "lodash/get";

import { cn } from "@/lib/utils";
import { Button } from "@/ui/button";
import { Input } from "@/ui/input";
import { Textarea } from "@/ui/textarea";
import { FormErrorSkeleton } from "@/ui/form";
import { LLM_SCHEMA_TYPE, LLMJudgeSchema } from "@/types/llm";
import { EvaluationRuleFormType } from "@/v2/pages-shared/automations/RuleEditorPanel/schema";
import RuleSettingsCard from "@/v2/pages-shared/automations/RuleEditorPanel/RuleSettingsCard";
import ScoreBlock from "@/v2/pages-shared/automations/RuleEditorPanel/ScoreBlock";

const NEW_QUESTION: LLMJudgeSchema = {
  name: "",
  type: LLM_SCHEMA_TYPE.BOOLEAN,
  description: "",
  unsaved: false,
};

type JevQuestionsCardProps = {
  form: UseFormReturn<EvaluationRuleFormType>;
};

const JevQuestionsCard: React.FC<JevQuestionsCardProps> = ({ form }) => {
  const questions: LLMJudgeSchema[] =
    form.watch("llmJudgeDetails.schema") ?? [];
  const errors = form.formState.errors;

  const setQuestions = useCallback(
    (next: LLMJudgeSchema[]) =>
      form.setValue("llmJudgeDetails.schema", next, { shouldDirty: true }),
    [form],
  );

  const updateQuestion = (index: number, changes: Partial<LLMJudgeSchema>) =>
    setQuestions(
      questions.map((question, i) =>
        i === index ? { ...question, ...changes } : question,
      ),
    );

  const getError = (index: number, field: "name" | "description") =>
    get(errors, ["llmJudgeDetails", "schema", index, field, "message"]) as
      | string
      | undefined;

  const listError: string | undefined = get(errors, [
    "llmJudgeDetails",
    "schema",
    "message",
  ]);

  return (
    <RuleSettingsCard
      icon={CircleHelp}
      iconClassName="bg-[var(--rule-icon-jev)]"
      title="Questions"
      description="Jev answers each question with yes or no. Each question is one score."
    >
      <div className="flex flex-col gap-1.5 p-2">
        {questions.map((question, index) => {
          const isMain = index === 0;
          const nameError = getError(index, "name");
          const questionError = getError(index, "description");
          return (
            <ScoreBlock
              key={index}
              title={isMain ? "Main question" : `Question ${index + 1}`}
              onRemove={
                isMain
                  ? undefined
                  : () => setQuestions(questions.filter((_, i) => i !== index))
              }
            >
              {!isMain && (
                <>
                  <Input
                    dimension="sm"
                    placeholder="Score name"
                    value={question.name}
                    onChange={(event) =>
                      updateQuestion(index, { name: event.target.value })
                    }
                    className={cn(nameError && "border-destructive")}
                  />
                  {nameError && (
                    <FormErrorSkeleton>{nameError}</FormErrorSkeleton>
                  )}
                </>
              )}
              <Textarea
                placeholder="For example: Does the output fully answer the input?"
                value={question.description}
                onChange={(event) =>
                  updateQuestion(index, { description: event.target.value })
                }
                className={cn(
                  "h-[72px] min-h-[72px] resize-none",
                  questionError && "border-destructive",
                )}
              />
              {questionError && (
                <FormErrorSkeleton>{questionError}</FormErrorSkeleton>
              )}
            </ScoreBlock>
          );
        })}
        {listError && <FormErrorSkeleton>{listError}</FormErrorSkeleton>}
        <div className="flex h-8 w-full items-center justify-center rounded-md border border-dashed border-border bg-soft-background">
          <Button
            type="button"
            variant="ghost"
            size="xs"
            className="text-foreground hover:text-primary-hover"
            onClick={() => setQuestions([...questions, { ...NEW_QUESTION }])}
          >
            <Plus className="mr-0.5 size-3" />
            Add question
          </Button>
        </div>
      </div>
    </RuleSettingsCard>
  );
};

export default JevQuestionsCard;
