import SliderInputControl from "@/components/shared/SliderInputControl/SliderInputControl";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { Checkbox } from "@/components/ui/checkbox";
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";
import { Textarea } from "@/components/ui/textarea";
import { cn, updateTextAreaHeight } from "@/lib/utils";
import { CheckedState } from "@radix-ui/react-checkbox";
import { Info } from "lucide-react";
import React, { useCallback, useEffect, useRef, useState } from "react";

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

const restrictedLabelList = [
  "Address",
  "Age",
  "Name",
  "Email",
  "Phone number",
  "Password",
  "Credit card number",
  "Bank account number",
  "IP address",
];
type RestrictedListProps = {
  id: string;
  value: string[];
  label?: string;
  onChange: (v: string[]) => void;
};
const RestrictedList: React.FC<RestrictedListProps> = ({
  id,
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
      {restrictedLabelList.map((label, idx) => (
        <Label key={id + idx} className="flex h-10 items-center gap-2">
          <Checkbox
            id={id + idx}
            checked={value.includes(label)}
            onCheckedChange={(v) => onCheckedChange(v, label)}
          />

          <div className="comet-body-s">{label}</div>
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
  label = "Denied topics",
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
  defaultEnabled?: boolean;
  className?: string;
  children: React.ReactNode;
};

const GuardrailConfig: GuardrailConfigComponents &
  React.FC<GuardrailConfigProps> = ({
  defaultEnabled,
  title,
  hintText,
  className,
  children,
}) => {
  const [enabled, setEnabled] = useState(Boolean(defaultEnabled));

  return (
    <div className={cn(className)}>
      <div className="flex h-10 items-center justify-between">
        <div className="flex items-center">
          <Label>{title}</Label>
          <TooltipWrapper content={hintText}>
            <Info className="ml-1 size-3.5 text-light-slate" />
          </TooltipWrapper>
        </div>
        <Switch size="sm" checked={enabled} onCheckedChange={setEnabled} />
      </div>
      {enabled && <div className="space-y-4 pb-3">{children}</div>}
    </div>
  );
};

GuardrailConfig.Threshold = Threshold;
GuardrailConfig.TopicsList = TopicsList;
GuardrailConfig.RestrictedList = RestrictedList;

export default GuardrailConfig;
