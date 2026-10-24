import React from "react";

import { Textarea } from "@/ui/textarea";
import { FormErrorSkeleton } from "@/ui/form";
import { LLMJudgeSchema } from "@/types/llm";
import ScoreBlock from "@/v2/pages-shared/automations/RuleEditorPanel/ScoreBlock";
import ScoreTypeSelect from "@/v2/pages-shared/automations/RuleEditorPanel/ScoreTypeSelect";
import ScoreNameTitle from "@/v2/pages-shared/automations/RuleEditorPanel/ScoreNameTitle";

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
  <ScoreBlock
    title={title}
    heading={
      <ScoreNameTitle
        name={score.name}
        hasError={Boolean(nameError)}
        onChange={(name) => onChange({ name })}
      />
    }
    onRemove={onRemove}
  >
    {nameError && <FormErrorSkeleton>{nameError}</FormErrorSkeleton>}
    <ScoreTypeSelect
      value={score.type}
      onChange={(type) => onChange({ type })}
      className="h-8 px-2"
    />
    <Textarea
      placeholder="Instruct the LLM on how to compute this score"
      value={score.description}
      onChange={(event) => onChange({ description: event.target.value })}
      className="h-[92px] min-h-[92px] resize-none"
    />
  </ScoreBlock>
);

export default AdditionalScore;
