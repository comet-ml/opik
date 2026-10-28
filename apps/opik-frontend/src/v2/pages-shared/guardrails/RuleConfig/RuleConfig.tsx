import React, { useCallback, useEffect, useMemo, useRef } from "react";
import { CheckedState } from "@radix-ui/react-checkbox";

import { Checkbox } from "@/ui/checkbox";
import { Description } from "@/ui/description";
import { Label } from "@/ui/label";
import { Switch } from "@/ui/switch";
import { Textarea } from "@/ui/textarea";
import { FormErrorSkeleton } from "@/ui/form";
import { cn, updateTextAreaHeight } from "@/lib/utils";
import { GuardrailPolicyRuleType } from "@/types/guardrails";
import {
  GuardrailRuleTypeDescriptionMap,
  GuardrailRuleTypeIconMap,
  GuardrailRuleTypeLabelMap,
  PIIEntitiesLabelMap,
  POLICY_PII_ENTITIES,
} from "@/constants/guardrails";

/** The border treatment the rest of the app puts on a field in error. */
const ERROR_FIELD_CLASS =
  "!border-destructive focus-visible:!border-destructive";

/** Widened so an entity stored through the API but absent from the offered list can be labelled. */
const PII_ENTITY_LABELS: Record<string, string> = PIIEntitiesLabelMap;

type PIIEntitiesProps = {
  value: string[];
  error?: string;
  onChange: (v: string[]) => void;
};

const PIIEntities: React.FC<PIIEntitiesProps> = ({
  value,
  error,
  onChange,
}) => {
  const onCheckedChange = (checked: CheckedState, entity: string) => {
    onChange(
      checked ? [...value, entity] : value.filter((item) => item !== entity),
    );
  };

  // A policy written through the API can name a class the form does not offer. Those extras are
  // appended so editing such a policy does not silently drop them on save.
  const entities = useMemo(() => {
    const offered: string[] = POLICY_PII_ENTITIES;
    return [...offered, ...value.filter((entity) => !offered.includes(entity))];
  }, [value]);

  return (
    <div className="grid w-full gap-1.5">
      <Label>Personal data to detect</Label>
      <div className="grid grid-cols-2 gap-x-4">
        {entities.map((entity) => (
          <Label
            key={entity}
            className="flex h-8 cursor-pointer items-center gap-2 font-normal"
          >
            <Checkbox
              checked={value.includes(entity)}
              onCheckedChange={(checked) => onCheckedChange(checked, entity)}
            />
            <span className="comet-body-s truncate">
              {PII_ENTITY_LABELS[entity] ?? entity}
            </span>
          </Label>
        ))}
      </div>
      {error && <FormErrorSkeleton>{error}</FormErrorSkeleton>}
    </div>
  );
};

type TopicsListProps = {
  id: string;
  value: string[];
  label: string;
  hint: string;
  error?: string;
  onChange: (v: string[]) => void;
};

const TopicsList: React.FC<TopicsListProps> = ({
  id,
  value,
  label,
  hint,
  error,
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
      <Label htmlFor={id}>
        {label} <span className="font-normal text-foreground">(optional)</span>
      </Label>
      <Textarea
        id={id}
        ref={callbackTextareaRef}
        placeholder="Topic 1, topic 2..."
        onChange={(event) => onChange(event.target.value.split(","))}
        value={value.join(",")}
        className={cn(
          "min-h-[70px] resize-none overflow-hidden",
          error && ERROR_FIELD_CLASS,
        )}
      />
      {error ? (
        <FormErrorSkeleton>{error}</FormErrorSkeleton>
      ) : (
        <Description>{hint} Use a comma to separate topics.</Description>
      )}
    </div>
  );
};

type RuleConfigComponents = {
  PIIEntities: typeof PIIEntities;
  TopicsList: typeof TopicsList;
};

type RuleConfigProps = {
  type: GuardrailPolicyRuleType;
  enabled: boolean;
  toggleEnabled: () => void;
  className?: string;
  children?: React.ReactNode;
};

// Validation messages belong to the individual controls, not here: each control above takes its
// own `error`, so a message renders against the field it is about.
const RuleConfig: RuleConfigComponents & React.FC<RuleConfigProps> = ({
  type,
  enabled,
  toggleEnabled,
  className,
  children,
}) => {
  const Icon = GuardrailRuleTypeIconMap[type];

  return (
    <div className={cn("py-2.5", enabled && children && "pb-4", className)}>
      <div className="flex items-center justify-between gap-4">
        <div className="flex min-w-0 items-center gap-1.5">
          <Icon className="size-3.5 shrink-0 text-light-slate" />
          <Label>{GuardrailRuleTypeLabelMap[type]}</Label>
        </div>
        <Switch
          size="sm"
          className="shrink-0"
          checked={enabled}
          onCheckedChange={toggleEnabled}
        />
      </div>
      <Description className="mt-0.5 block">
        {GuardrailRuleTypeDescriptionMap[type]}
      </Description>
      {enabled && children && <div className="mt-3 space-y-4">{children}</div>}
    </div>
  );
};

RuleConfig.PIIEntities = PIIEntities;
RuleConfig.TopicsList = TopicsList;

export default RuleConfig;
