import React, { useEffect, useState } from "react";
import isEqual from "fast-deep-equal";
import TextareaAutosize from "react-textarea-autosize";

import { Check, Pencil, Trash } from "lucide-react";

import { cn } from "@/lib/utils";
import { LLMJudgeSchema } from "@/types/llm";
import { LLM_SCHEMA_TYPE, ScoresValidationError } from "@/types/llm";
import { Card, CardContent } from "@/components/ui/card";
import SelectBox from "@/components/shared/SelectBox/SelectBox";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { DropdownOption } from "@/types/shared";
import { Button } from "@/components/ui/button";
import { FormErrorSkeleton } from "@/components/ui/form";
import { Input } from "@/components/ui/input";
import { TEXT_AREA_CLASSES } from "@/components/ui/textarea";
import { get } from "lodash";

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

type ScoreFieldData = Omit<LLMJudgeSchema, "unsaved">;

interface LLMJudgeScoreProps {
  hideRemoveButton: boolean;
  error?: ScoresValidationError;
  score: LLMJudgeSchema;
  onRemoveScore: () => void;
  onChangeScore: (changes: Partial<LLMJudgeSchema>) => void;
}

const LLMJudgeScore = ({
  hideRemoveButton,
  error,
  score,
  onChangeScore,
  onRemoveScore,
}: LLMJudgeScoreProps) => {
  const [isEditing, setIsEditing] = useState(false);
  const [scoreData, setScoreData] = useState<ScoreFieldData>({
    name: score.name || "",
    description: score.description || "",
    type: score.type || LLM_SCHEMA_TYPE.INTEGER,
  });

  useEffect(() => {
    setScoreData({
      name: score.name,
      description: score.description,
      type: score.type,
    });
  }, [score.description, score.name, score.type]);

  const handleDoneEditing = () => {
    const newScore = {
      ...scoreData,
      unsaved: false,
    };

    if (!isEqual(score, newScore)) {
      onChangeScore(newScore);
    }

    setIsEditing(false);
  };

  const onUpdateField = (value: string, fieldKey: keyof ScoreFieldData) => {
    const isChanged = scoreData[fieldKey] !== value;

    if (isChanged && !score.unsaved) {
      onChangeScore({
        unsaved: true,
      });
    }
    setScoreData((prev) => ({
      ...prev,
      [fieldKey]: value,
    }));
  };

  const nameErrorText = get(error, ["name", "message"]);
  const unsavedErrorText = get(error, ["unsaved", "message"]);

  return (
    <div className="flex flex-col gap-1">
      <Card
        className={cn("relative p-3", {
          "border-destructive": unsavedErrorText,
        })}
      >
        <CardContent className="flex justify-between gap-2 p-0">
          <div className="flex min-w-1 basis-2/3 flex-col gap-1">
            {!isEditing ? (
              <>
                <div className="comet-body-s truncate break-words">
                  {score.name}
                </div>
                {nameErrorText && (
                  <FormErrorSkeleton className="my-1">
                    {nameErrorText}
                  </FormErrorSkeleton>
                )}
                <div className="comet-body-s line-clamp-3 break-words text-muted-slate">
                  {score.description}
                </div>
              </>
            ) : (
              <>
                <Input
                  dimension="sm"
                  placeholder="Score name"
                  className={cn({
                    "border-destructive": nameErrorText,
                  })}
                  value={scoreData.name}
                  onChange={(event) =>
                    onUpdateField(event.target.value, "name")
                  }
                />
                {nameErrorText && (
                  <FormErrorSkeleton className="my-1">
                    {nameErrorText}
                  </FormErrorSkeleton>
                )}
                <TextareaAutosize
                  placeholder="Score description"
                  value={scoreData.description}
                  onChange={(event) =>
                    onUpdateField(event.target.value, "description")
                  }
                  className={cn(TEXT_AREA_CLASSES, "min-h-8 leading-none")}
                  minRows={1}
                  maxRows={3}
                />
              </>
            )}
          </div>
          <div className="flex basis-1/3 gap-2">
            <SelectBox
              value={scoreData.type}
              onChange={(value) =>
                onUpdateField(value as LLM_SCHEMA_TYPE, "type")
              }
              disabled={!isEditing}
              options={SCORE_TYPE_OPTIONS}
              className="h-8"
            />

            {!isEditing ? (
              <TooltipWrapper content="Edit a score">
                <Button
                  variant="outline"
                  size="icon-sm"
                  className="shrink-0"
                  onClick={() => setIsEditing(true)}
                  type="button"
                >
                  <Pencil />
                </Button>
              </TooltipWrapper>
            ) : (
              <TooltipWrapper content="Done editing">
                <Button
                  variant="secondary"
                  size="sm"
                  onClick={handleDoneEditing}
                  className="shrink-0"
                  type="button"
                >
                  <Check className="mr-1.5 size-3.5 shrink-0" />
                  Done editing
                </Button>
              </TooltipWrapper>
            )}

            <TooltipWrapper content="Delete a score">
              <Button
                variant="outline"
                size="icon-sm"
                onClick={onRemoveScore}
                className="shrink-0"
                disabled={hideRemoveButton}
              >
                <Trash />
              </Button>
            </TooltipWrapper>
          </div>
        </CardContent>
      </Card>
      {unsavedErrorText && (
        <FormErrorSkeleton className="my-1">
          {unsavedErrorText}
        </FormErrorSkeleton>
      )}
    </div>
  );
};

export default LLMJudgeScore;
