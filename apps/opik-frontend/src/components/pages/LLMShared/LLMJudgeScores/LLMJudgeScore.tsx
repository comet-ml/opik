import React, { useState } from "react";
import isEqual from "fast-deep-equal";

import { Check, Pencil, Trash } from "lucide-react";

import { LLMJudgeSchema } from "@/types/automations";
import { LLM_SCHEMA_TYPE } from "@/types/llm";
import { Card, CardContent } from "@/components/ui/card";
import SelectBox from "@/components/shared/SelectBox/SelectBox";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { DropdownOption } from "@/types/shared";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";

interface LLMJudgeScoreProps {
  score: LLMJudgeSchema;
  onRemoveScore: () => void;
  onChangeScore: (changes: Partial<LLMJudgeSchema>) => void;
}

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

// TODO lala textarea with autogrow
const LLMJudgeScore = ({
  score,
  onChangeScore,
  onRemoveScore,
}: LLMJudgeScoreProps) => {
  const [isEditing, setIsEditing] = useState(false);
  const [name, setName] = useState(score.name || "");
  const [description, setDescription] = useState(score.description || "");
  const [type, setType] = useState<LLM_SCHEMA_TYPE>(
    score.type || LLM_SCHEMA_TYPE.INTEGER,
  );

  const handleDoneEditing = () => {
    const newScore = {
      name,
      description,
      type,
    };

    if (!isEqual(score, newScore)) {
      onChangeScore(newScore);
    }
    setIsEditing(false);
  };

  return (
    <Card className="relative p-3">
      <CardContent className="flex justify-between gap-2 p-0">
        <div className="flex min-w-1 basis-2/3 flex-col gap-1">
          {!isEditing ? (
            <>
              <div className="comet-body-s truncate break-words">
                {score.name}
              </div>
              <div className="comet-body-s line-clamp-3 break-words text-muted-slate">
                {score.description}
              </div>
            </>
          ) : (
            <>
              <Input
                dimension="sm"
                placeholder="Score name"
                value={name}
                onChange={(event) => setName(event.target.value)}
              />
              <Textarea
                placeholder="Score description"
                value={description}
                onChange={(event) => setDescription(event.target.value)}
                className="min-h-8 leading-none"
                rows={1}
              />
            </>
          )}
        </div>
        <div className="flex basis-1/3 gap-2">
          <SelectBox
            value={type}
            onChange={(value) => setType(value as LLM_SCHEMA_TYPE)}
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
              >
                <Pencil className="size-3.5" />
              </Button>
            </TooltipWrapper>
          ) : (
            <TooltipWrapper content="Done editing">
              <Button
                variant="outline"
                size="sm"
                onClick={handleDoneEditing}
                className="shrink-0"
              >
                <Check className="mr-2 size-3.5 shrink-0" />
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
            >
              <Trash className="size-3.5" />
            </Button>
          </TooltipWrapper>
        </div>
      </CardContent>
    </Card>
  );
};

export default LLMJudgeScore;
