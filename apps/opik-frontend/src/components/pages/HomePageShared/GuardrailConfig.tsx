import SliderInputControl from "@/components/shared/SliderInputControl/SliderInputControl";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { Checkbox } from "@/components/ui/checkbox";
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";
import { Textarea } from "@/components/ui/textarea";
import { cn, updateTextAreaHeight } from "@/lib/utils";
import { CheckedState } from "@radix-ui/react-checkbox";
import { Info } from "lucide-react";
import React, { useCallback, useEffect, useRef } from "react";
import { PiiSupportedEntities, PiiSupportedEntity } from "./guardrailsConfig";

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

const restrictedLabelMap = {
  [PiiSupportedEntities.CREDIT_CARD]: "Credit card number",
  [PiiSupportedEntities.PHONE_NUMBER]: "Phone number",
  [PiiSupportedEntities.EMAIL_ADDRESS]: "Email",
  [PiiSupportedEntities.IBAN_CODE]: "Bank account number",
  [PiiSupportedEntities.IP_ADDRESS]: "IP address",
  [PiiSupportedEntities.NRP]: "National Registration Plate",
  [PiiSupportedEntities.LOCATION]: "Address",
  [PiiSupportedEntities.PERSON]: "Name",
  [PiiSupportedEntities.CRYPTO]: "Cryptocurrency",
  [PiiSupportedEntities.MEDICAL_LICENSE]: "Medical license",
  [PiiSupportedEntities.URL]: "URL",
};
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

  const restrictedLabelList = Object.keys(restrictedLabelMap);

  return (
    <div className="grid w-full">
      <p className="comet-body-s-accented flex h-10 items-center">{label}</p>
      {restrictedLabelList.map((label) => (
        <Label
          key={label}
          className="flex h-10 cursor-pointer items-center gap-2"
        >
          <Checkbox
            id={label}
            checked={value.includes(label)}
            onCheckedChange={(v) => onCheckedChange(v, label)}
          />

          <div className="comet-body-s">
            {restrictedLabelMap[label as PiiSupportedEntity]}
          </div>
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

type GuardrailConfigComponents = {
  Threshold: typeof Threshold;
  TopicsList: typeof TopicsList;
  RestrictedList: typeof RestrictedList;
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

export default GuardrailConfig;
