import React from "react";
import TextareaAutosize from "react-textarea-autosize";
import { Gauge, Trash } from "lucide-react";
import get from "lodash/get";

import { cn } from "@/lib/utils";
import {
  LLM_SCHEMA_TYPE,
  LLMJudgeSchema,
  ScoresValidationError,
} from "@/types/llm";
import SelectBox from "@/shared/SelectBox/SelectBox";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { DropdownOption } from "@/types/shared";
import { Button } from "@/ui/button";
import { FormErrorSkeleton } from "@/ui/form";
import { Input } from "@/ui/input";
import { TEXT_AREA_CLASSES } from "@/ui/textarea";

const SCORE_TYPE_OPTIONS: DropdownOption<LLM_SCHEMA_TYPE>[] = [
  {
    value: LLM_SCHEMA_TYPE.DOUBLE,
    label: "Number",
  },
  {
    value: LLM_SCHEMA_TYPE.INTEGER,
    label: "Integer",
  },
  {
    value: LLM_SCHEMA_TYPE.BOOLEAN,
    label: "Boolean",
  },
];

interface LLMJudgeScoreProps {
  title: string;
  /** The main score takes its name from the rule, so the field is hidden unless they differ. */
  showName: boolean;
  showDescription: boolean;
  error?: ScoresValidationError;
  score: LLMJudgeSchema;
  onRemoveScore?: () => void;
  onChangeScore: (changes: Partial<LLMJudgeSchema>) => void;
}

/**
 * One score definition, edited in place — every keystroke commits to the
 * form, so there is no edit mode to remember to close.
 */
const LLMJudgeScore = ({
  title,
  showName,
  showDescription,
  error,
  score,
  onChangeScore,
  onRemoveScore,
}: LLMJudgeScoreProps) => {
  const nameErrorText = get(error, ["name", "message"]);

  return (
    <div
      className={cn("flex flex-col gap-2 rounded-md border border-border p-2", {
        "border-destructive": nameErrorText,
      })}
      data-testid="llm-judge-score"
    >
      <div className="flex h-6 items-center justify-between">
        <div className="comet-body-xs-accented flex items-center gap-1.5 text-muted-slate">
          <Gauge className="size-3.5" />
          {title}
        </div>
        {onRemoveScore && (
          <TooltipWrapper content="Delete score">
            <Button
              variant="minimal"
              size="icon-2xs"
              onClick={onRemoveScore}
              type="button"
              aria-label="Delete score"
            >
              <Trash />
            </Button>
          </TooltipWrapper>
        )}
      </div>
      <div className="flex items-start gap-2">
        {showName && (
          <Input
            dimension="sm"
            placeholder="Score name"
            aria-label="Score name"
            className={cn("min-w-0 flex-1", {
              "border-destructive": nameErrorText,
            })}
            value={score.name}
            onChange={(event) =>
              onChangeScore({ name: event.target.value, unsaved: false })
            }
          />
        )}
        <SelectBox
          value={score.type}
          onChange={(value) =>
            onChangeScore({ type: value as LLM_SCHEMA_TYPE, unsaved: false })
          }
          options={SCORE_TYPE_OPTIONS}
          className={cn("h-8", showName ? "w-36 shrink-0" : "w-full")}
        />
      </div>
      {showDescription && (
        <TextareaAutosize
          placeholder="Instruct the LLM on how to compute this score"
          aria-label="Score description"
          value={score.description}
          onChange={(event) =>
            onChangeScore({ description: event.target.value, unsaved: false })
          }
          className={cn(TEXT_AREA_CLASSES, "min-h-16 leading-snug")}
          minRows={2}
          maxRows={5}
        />
      )}
      {nameErrorText && <FormErrorSkeleton>{nameErrorText}</FormErrorSkeleton>}
    </div>
  );
};

export default LLMJudgeScore;
