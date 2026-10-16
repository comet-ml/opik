import React from "react";

import { cn } from "@/lib/utils";
import { Input } from "@/ui/input";
import { Textarea } from "@/ui/textarea";
import { FormErrorSkeleton } from "@/ui/form";
import { LLMJudgeSchema } from "@/types/llm";
import ScoreBlock from "@/v2/pages-shared/automations/RuleEditorPanel/ScoreBlock";
import ScoreTypeSelect from "@/v2/pages-shared/automations/RuleEditorPanel/ScoreTypeSelect";

type AdditionalScoreProps = {
  title: string;
  score: LLMJudgeSchema;
  nameError?: string;
  onChange: (changes: Partial<LLMJudgeSchema>) => void;
  onRemove: () => void;
};

const AdditionalScore: React.FC<AdditionalScoreProps> = ({
  title,
  score,
  nameError,
  onChange,
  onRemove,
}) => (
  <ScoreBlock title={title} onRemove={onRemove}>
    <div className="flex items-center gap-2">
      <Input
        dimension="sm"
        placeholder="Score name"
        value={score.name}
        onChange={(event) => onChange({ name: event.target.value })}
        className={cn("flex-1", nameError && "border-destructive")}
      />
      <ScoreTypeSelect
        value={score.type}
        onChange={(type) => onChange({ type })}
        className="h-8 flex-1 px-2"
      />
    </div>
    {nameError && <FormErrorSkeleton>{nameError}</FormErrorSkeleton>}
    <Textarea
      placeholder="Instruct the LLM on how to compute this score"
      value={score.description}
      onChange={(event) => onChange({ description: event.target.value })}
      className="h-[92px] min-h-[92px] resize-none"
    />
  </ScoreBlock>
);

export default AdditionalScore;
