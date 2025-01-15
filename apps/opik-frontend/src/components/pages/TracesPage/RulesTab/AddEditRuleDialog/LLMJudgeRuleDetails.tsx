import React, { useCallback, useRef } from "react";
import { Info } from "lucide-react";
import find from "lodash/find";
import last from "lodash/last";

import { LLMAsJudgeData, LLMJudgeSchema } from "@/types/automations";
import { Label } from "@/components/ui/label";
import PromptModelSelect from "@/components/pages-shared/llm/PromptModelSelect/PromptModelSelect";
import PromptModelConfigs from "@/components/pages-shared/llm/PromptModelSettings/PromptModelConfigs";
import SelectBox from "@/components/shared/SelectBox/SelectBox";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import LLMPromptMessages from "@/components/pages-shared/llm/LLMPromptMessages/LLMPromptMessages";
import LLMPromptMessagesVariables from "@/components/pages-shared/llm/LLMPromptMessagesVariables/LLMPromptMessagesVariables";
import LLMJudgeScores from "@/components/pages-shared/llm/LLMJudgeScores/LLMJudgeScores";
import { LLM_PROMPT_TEMPLATES } from "@/constants/llm";
import {
  LLM_JUDGE,
  LLM_MESSAGE_ROLE,
  LLMMessage,
  LLMPromptTemplate,
} from "@/types/llm";
import {
  generateDefaultLLMPromptMessage,
  getModelProvider,
  getNextMessageType,
} from "@/lib/llm";
import { OnChangeFn } from "@/types/shared";
import { LLMPromptConfigsType, PROVIDER_MODEL_TYPE } from "@/types/providers";
import { safelyGetPromptMustacheTags } from "@/lib/prompt";

type LLMJudgeRuleDetailsProps = {
  workspaceName: string;
  data: LLMAsJudgeData;
  onChange: OnChangeFn<LLMAsJudgeData>;
};

const LLMJudgeRuleDetails: React.FC<LLMJudgeRuleDetailsProps> = ({
  workspaceName,
  data,
  onChange,
}) => {
  const {
    model,
    config,
    template,
    messages,
    variables,
    schema,
    parsingVariablesError,
  } = data;
  const provider = model ? getModelProvider(model) : "";

  const cache = useRef<Record<string | LLM_JUDGE, LLMPromptTemplate>>({});

  const setModel = useCallback(
    (model: PROVIDER_MODEL_TYPE) => {
      onChange((d) => ({ ...d, model }));
    },
    [onChange],
  );

  const setConfig = useCallback(
    (config: Partial<LLMPromptConfigsType>) => {
      onChange((d) => ({ ...d, config }));
    },
    [onChange],
  );

  const setMessages = useCallback(
    (messages: LLMMessage[]) => {
      onChange((d) => {
        const variables: Record<string, string> = {};
        let parsingVariablesError: boolean = false;
        messages
          .reduce<string[]>((acc, m) => {
            const tags = safelyGetPromptMustacheTags(m.content);
            if (!tags) {
              parsingVariablesError = true;
              return acc;
            } else {
              return acc.concat(tags);
            }
          }, [])
          .filter((v) => v !== "")
          .forEach((v: string) => (variables[v] = d.variables[v] ?? ""));

        return { ...d, messages, variables, parsingVariablesError };
      });
    },
    [onChange],
  );

  const setVariables = useCallback(
    (variables: Record<string, string>) => {
      onChange((d) => ({ ...d, variables }));
    },
    [onChange],
  );

  const setSchema = useCallback(
    (schema: LLMJudgeSchema[]) => {
      onChange((d) => ({ ...d, schema }));
    },
    [onChange],
  );

  const handleTemplateChange = useCallback(
    (newTemplate: LLM_JUDGE) => {
      onChange((d) => {
        if (newTemplate === d.template) return d;

        cache.current[d.template] = {
          ...cache.current[d.template],
          messages: d.messages,
          variables: d.variables,
          schema: d.schema,
        };

        const templateData =
          cache.current[newTemplate] ??
          find(LLM_PROMPT_TEMPLATES, (t) => t.value === newTemplate);

        return {
          ...d,
          messages: templateData.messages,
          variables: templateData.variables,
          schema: templateData.schema,
          template: newTemplate,
        };
      });
    },
    [onChange],
  );

  const handleAddMessage = useCallback(() => {
    onChange((d) => {
      const newMessage = generateDefaultLLMPromptMessage();
      const lastMessage = last(d.messages);

      newMessage.role = lastMessage
        ? getNextMessageType(lastMessage!)
        : LLM_MESSAGE_ROLE.system;
      return { ...d, messages: [...d.messages, newMessage] };
    });
  }, [onChange]);

  return (
    <>
      <div className="flex flex-col gap-2 py-4">
        <Label htmlFor="name">Model</Label>
        <div className="flex h-10 items-center justify-center gap-2">
          <PromptModelSelect
            value={model}
            onChange={setModel}
            provider={provider}
            workspaceName={workspaceName}
            onlyWithStructuredOutput
          />
          <PromptModelConfigs
            size="icon"
            provider={provider}
            configs={config}
            onChange={setConfig as never}
          />
        </div>
      </div>
      <div className="flex flex-col gap-2 pb-4">
        <Label htmlFor="name">Prompt</Label>
        <SelectBox
          value={template}
          onChange={(value) => handleTemplateChange(value as LLM_JUDGE)}
          options={LLM_PROMPT_TEMPLATES}
        />
        <LLMPromptMessages
          messages={messages}
          onChange={setMessages}
          onAddMessage={handleAddMessage}
        />
        <LLMPromptMessagesVariables
          hasError={parsingVariablesError}
          variables={variables}
          onChange={setVariables}
        />
      </div>

      <div className="flex flex-col gap-2 pb-4">
        <div className="flex items-center">
          <Label htmlFor="name">Score definition</Label>
          <TooltipWrapper
            content={`The score definition is used to define which
feedback scores are returned by this rule.
To return more than one score, simply add
multiple scores to this section.`}
          >
            <Info className="ml-1 size-4 text-light-slate" />
          </TooltipWrapper>
        </div>
        <LLMJudgeScores scores={schema} onChange={setSchema} />
      </div>
    </>
  );
};

export default LLMJudgeRuleDetails;
