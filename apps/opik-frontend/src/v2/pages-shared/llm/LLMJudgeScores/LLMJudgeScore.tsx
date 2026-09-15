import React from "react";
import TextareaAutosize from "react-textarea-autosize";
import { Trash } from "lucide-react";
import get from "lodash/get";

import { cn } from "@/lib/utils";
import {
  LLM_SCHEMA_TYPE,
  LLMJudgeSchema,
  ScoresValidationError,
} from "@/types/llm";
import { Card, CardContent } from "@/ui/card";
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
  hideRemoveButton: boolean;
  error?: ScoresValidationError;
  score: LLMJudgeSchema;
  onRemoveScore: () => void;
  onChangeScore: (changes: Partial<LLMJudgeSchema>) => void;
}

/**
 * One score definition, edited in place. Every keystroke is committed to the
 * form straight away — there is no separate edit mode to remember to close, so
 * the "unsaved" state the schema still knows about can never be reached from
 * here.
 */
const LLMJudgeScore = ({
  hideRemoveButton,
  error,
  score,
  onChangeScore,
  onRemoveScore,
}: LLMJudgeScoreProps) => {
  const nameErrorText = get(error, ["name", "message"]);

  return (
    <Card
      className={cn("p-3", {
        "border-destructive": nameErrorText,
      })}
      data-testid="llm-judge-score"
    >
      <CardContent className="flex flex-col gap-2 p-0">
        <div className="flex items-start gap-2">
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
          <SelectBox
            value={score.type}
            onChange={(value) =>
              onChangeScore({ type: value as LLM_SCHEMA_TYPE, unsaved: false })
            }
            options={SCORE_TYPE_OPTIONS}
            className="h-8 w-32 shrink-0"
          />
          <TooltipWrapper content="Delete score">
            <Button
              variant="outline"
              size="icon-sm"
              onClick={onRemoveScore}
              className="shrink-0"
              disabled={hideRemoveButton}
              type="button"
              aria-label="Delete score"
            >
              <Trash />
            </Button>
          </TooltipWrapper>
        </div>
        <TextareaAutosize
          placeholder="Describe what this score measures"
          aria-label="Score description"
          value={score.description}
          onChange={(event) =>
            onChangeScore({ description: event.target.value, unsaved: false })
          }
          className={cn(TEXT_AREA_CLASSES, "min-h-8 leading-none")}
          minRows={1}
          maxRows={3}
        />
        {nameErrorText && (
          <FormErrorSkeleton>{nameErrorText}</FormErrorSkeleton>
        )}
      </CardContent>
    </Card>
  );
};

export default LLMJudgeScore;
