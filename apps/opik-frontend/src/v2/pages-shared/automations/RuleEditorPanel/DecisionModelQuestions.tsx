import React, { useCallback } from "react";
import { UseFormReturn } from "react-hook-form";
import { CircleHelp } from "lucide-react";
import get from "lodash/get";

import { cn } from "@/lib/utils";
import { Textarea } from "@/ui/textarea";
import { FormErrorSkeleton } from "@/ui/form";
import AddItemButton from "@/shared/AddItemButton/AddItemButton";
import { LLM_SCHEMA_TYPE, LLMJudgeSchema } from "@/types/llm";
import { EvaluationRuleFormType } from "@/v2/pages-shared/automations/RuleEditorPanel/schema";
import ScoreBlock from "@/v2/pages-shared/automations/RuleEditorPanel/ScoreBlock";
import ScoreNameTitle from "@/v2/pages-shared/automations/RuleEditorPanel/ScoreNameTitle";

const NEW_QUESTION: LLMJudgeSchema = {
  name: "",
  type: LLM_SCHEMA_TYPE.BOOLEAN,
  description: "",
  unsaved: false,
};

type DecisionModelQuestionsProps = {
  form: UseFormReturn<EvaluationRuleFormType>;
};

const DecisionModelQuestions: React.FC<DecisionModelQuestionsProps> = ({
  form,
}) => {
  const questions: LLMJudgeSchema[] =
    form.watch("llmJudgeDetails.schema") ?? [];
  const ruleName = form.watch("ruleName").trim();
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
    <div className="flex flex-col gap-1.5 bg-soft-background p-2">
      <p className="comet-body-s px-0.5">
        The decision model answers each question with yes or no. Each question
        is one score.
      </p>
      {questions.map((question, index) => {
        const isMain = index === 0;
        const nameError = getError(index, "name");
        const questionError = getError(index, "description");
        return (
          <ScoreBlock
            key={index}
            title={isMain ? "Main question" : `Question ${index + 1}`}
            icon={CircleHelp}
            iconClassName="bg-[var(--rule-icon-decision-model)]"
            heading={
              <ScoreNameTitle
                name={question.name}
                fallbackName={isMain ? ruleName : undefined}
                hasError={Boolean(nameError)}
                onChange={(name) => updateQuestion(index, { name })}
              />
            }
            onRemove={
              isMain
                ? undefined
                : () => setQuestions(questions.filter((_, i) => i !== index))
            }
          >
            {nameError && <FormErrorSkeleton>{nameError}</FormErrorSkeleton>}
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
      <AddItemButton
        label="Add question"
        onClick={() => setQuestions([...questions, { ...NEW_QUESTION }])}
      />
    </div>
  );
};

export default DecisionModelQuestions;
