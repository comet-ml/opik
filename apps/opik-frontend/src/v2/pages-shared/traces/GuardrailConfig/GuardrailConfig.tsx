import SliderInputControl from "@/shared/SliderInputControl/SliderInputControl";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { Checkbox } from "@/ui/checkbox";
import { Input } from "@/ui/input";
import { Label } from "@/ui/label";
import { Switch } from "@/ui/switch";
import { Textarea } from "@/ui/textarea";
import { cn, updateTextAreaHeight } from "@/lib/utils";
import { CheckedState } from "@radix-ui/react-checkbox";
import { Info } from "lucide-react";
import React, { useCallback, useEffect, useRef } from "react";
import { PiiSupportedEntities } from "@/types/guardrails";
import { PIIEntitiesLabelMap } from "@/constants/guardrails";
import PromptModelSelect from "@/v2/pages-shared/llm/PromptModelSelect/PromptModelSelect";
import useLLMProviderModelsData from "@/hooks/useLLMProviderModelsData";
import { COMPOSED_PROVIDER_TYPE, PROVIDER_MODEL_TYPE } from "@/types/providers";

type ThresholdProps = {
  id: string;
  value: number;
  label?: string;
  onChange: (v: number) => void;
};
const Threshold: React.FC<ThresholdProps> = ({
  id,
  value,
  label = "Sensitivity",
  onChange,
}) => {
  return (
    <SliderInputControl
      value={value}
      onChange={onChange}
      id={id}
      min={0}
      max={1}
      step={0.01}
      label={label}
      defaultValue={0}
      resetDisabled
    />
  );
};

const RESTRICTED_LABEL_LIST: PiiSupportedEntities[] = [
  PiiSupportedEntities.CREDIT_CARD,
  PiiSupportedEntities.PHONE_NUMBER,
  PiiSupportedEntities.EMAIL_ADDRESS,
  PiiSupportedEntities.IBAN_CODE,
  PiiSupportedEntities.IP_ADDRESS,
  PiiSupportedEntities.NRP,
  PiiSupportedEntities.LOCATION,
  PiiSupportedEntities.PERSON,
  PiiSupportedEntities.CRYPTO,
  PiiSupportedEntities.MEDICAL_LICENSE,
  PiiSupportedEntities.URL,
];

type RestrictedListProps = {
  value: string[];
  label?: string;
  onChange: (v: string[]) => void;
};
const RestrictedList: React.FC<RestrictedListProps> = ({
  value,
  label = "Restricted personal data",
  onChange,
}) => {
  const onCheckedChange = (checked: CheckedState, label: string) => {
    const newList = checked
      ? [...value, label]
      : value.filter((v) => v !== label);

    onChange(newList);
  };

  return (
    <div className="grid w-full">
      <p className="comet-body-s-accented flex h-10 items-center">{label}</p>
      {RESTRICTED_LABEL_LIST.map((label) => (
        <Label
          key={label}
          className="flex h-10 cursor-pointer items-center gap-2"
        >
          <Checkbox
            id={label}
            checked={value.includes(label)}
            onCheckedChange={(v) => onCheckedChange(v, label)}
          />

          <div className="comet-body-s">{PIIEntitiesLabelMap[label]}</div>
        </Label>
      ))}
    </div>
  );
};

type TopicsListProps = {
  id: string;
  value: string[];
  label?: string;
  onChange: (v: string[]) => void;
};
const TopicsList: React.FC<TopicsListProps> = ({
  id,
  value,
  label = "Restricted topics",
  onChange,
}) => {
  const textAreaRef = useRef<HTMLTextAreaElement | null>(null);

  useEffect(() => {
    updateTextAreaHeight(textAreaRef.current);
  }, [value]);

  const callbackTextareaRef = useCallback((e: HTMLTextAreaElement | null) => {
    textAreaRef.current = e;
    updateTextAreaHeight(e, 70);
  }, []);

  const onTextareaChange: React.ChangeEventHandler<HTMLTextAreaElement> = (
    e,
  ) => {
    onChange(e.target.value.split(","));
  };
  const textareaValue = value.join(",");

  return (
    <div className="grid w-full gap-1">
      <Label htmlFor={id}>{label}</Label>
      <Textarea
        id={id}
        ref={callbackTextareaRef}
        placeholder="Topic 1, topic 2..."
        onChange={onTextareaChange}
        value={textareaValue}
        className="min-h-[70px] resize-none overflow-hidden"
      />
      <p className="comet-body-s text-light-slate">
        Use a comma to separate topics
      </p>
    </div>
  );
};

type TextInputProps = {
  id: string;
  value: string;
  label: string;
  placeholder?: string;
  onChange: (v: string) => void;
};
const TextInput: React.FC<TextInputProps> = ({
  id,
  value,
  label,
  placeholder,
  onChange,
}) => {
  return (
    <div className="grid w-full gap-1">
      <Label htmlFor={id}>{label}</Label>
      <Input
        id={id}
        value={value}
        placeholder={placeholder}
        onChange={(e) => onChange(e.target.value)}
      />
    </div>
  );
};

type InstructionsProps = {
  id: string;
  value: string;
  label?: string;
  placeholder?: string;
  onChange: (v: string) => void;
};
const Instructions: React.FC<InstructionsProps> = ({
  id,
  value,
  label = "Instructions",
  placeholder = "Describe the policy the text must comply with...",
  onChange,
}) => {
  const textAreaRef = useRef<HTMLTextAreaElement | null>(null);

  useEffect(() => {
    updateTextAreaHeight(textAreaRef.current);
  }, [value]);

  const callbackTextareaRef = useCallback((e: HTMLTextAreaElement | null) => {
    textAreaRef.current = e;
    updateTextAreaHeight(e, 70);
  }, []);

  return (
    <div className="grid w-full gap-1">
      <Label htmlFor={id}>{label}</Label>
      <Textarea
        id={id}
        ref={callbackTextareaRef}
        placeholder={placeholder}
        onChange={(e) => onChange(e.target.value)}
        value={value}
        className="min-h-[70px] resize-none overflow-hidden"
      />
    </div>
  );
};

type ModelSelectProps = {
  value: string;
  workspaceName: string;
  onChange: (v: string) => void;
};
const ModelSelect: React.FC<ModelSelectProps> = ({
  value,
  workspaceName,
  onChange,
}) => {
  const { calculateModelProvider, calculateDefaultModel } =
    useLLMProviderModelsData();
  const model = value as PROVIDER_MODEL_TYPE | "";
  const provider = calculateModelProvider(model);

  const handleAddProvider = useCallback(
    (addedProvider: COMPOSED_PROVIDER_TYPE) => {
      if (!model) {
        onChange(calculateDefaultModel(model, [addedProvider], addedProvider));
      }
    },
    [calculateDefaultModel, model, onChange],
  );

  const handleDeleteProvider = useCallback(
    (deletedProvider: COMPOSED_PROVIDER_TYPE) => {
      if (calculateModelProvider(model, deletedProvider) === deletedProvider) {
        onChange("");
      }
    },
    [calculateModelProvider, model, onChange],
  );

  return (
    <div className="grid w-full gap-1">
      <Label>Model</Label>
      <PromptModelSelect
        value={model}
        provider={provider}
        workspaceName={workspaceName}
        onChange={(m) => onChange(m)}
        onAddProvider={handleAddProvider}
        onDeleteProvider={handleDeleteProvider}
      />
    </div>
  );
};

type GuardrailConfigComponents = {
  Threshold: typeof Threshold;
  TopicsList: typeof TopicsList;
  RestrictedList: typeof RestrictedList;
  TextInput: typeof TextInput;
  Instructions: typeof Instructions;
  ModelSelect: typeof ModelSelect;
};

type GuardrailConfigProps = {
  title: string;
  hintText: string;
  enabled: boolean;
  toggleEnabled: () => void;
  className?: string;
  children: React.ReactNode;
};

const GuardrailConfig: GuardrailConfigComponents &
  React.FC<GuardrailConfigProps> = ({
  title,
  hintText,
  enabled,
  toggleEnabled,
  className,
  children,
}) => {
  return (
    <div className={cn(className)}>
      <div className="flex h-10 items-center justify-between">
        <div className="flex items-center">
          <Label>{title}</Label>
          <TooltipWrapper content={hintText}>
            <Info className="ml-1 size-3.5 text-light-slate" />
          </TooltipWrapper>
        </div>
        <Switch size="sm" checked={enabled} onCheckedChange={toggleEnabled} />
      </div>
      {enabled && <div className="space-y-4 pb-3">{children}</div>}
    </div>
  );
};

GuardrailConfig.Threshold = Threshold;
GuardrailConfig.TopicsList = TopicsList;
GuardrailConfig.RestrictedList = RestrictedList;
GuardrailConfig.TextInput = TextInput;
GuardrailConfig.Instructions = Instructions;
GuardrailConfig.ModelSelect = ModelSelect;

export default GuardrailConfig;
