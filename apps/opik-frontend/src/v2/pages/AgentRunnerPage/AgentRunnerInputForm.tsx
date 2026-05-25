import React, { useEffect, useMemo, useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { ChevronDown, ChevronRight } from "lucide-react";

import { Input } from "@/ui/input";
import { Textarea } from "@/ui/textarea";
import { Switch } from "@/ui/switch";

type ParamPresence = "required" | "optional";

type AgentParam = {
  name: string;
  type: string;
  presence?: ParamPresence;
};

type NormalizedType =
  | "boolean"
  | "numeric-int"
  | "numeric-float"
  | "object"
  | "text";

const BOOL_TYPES = new Set(["bool", "boolean"]);
const INT_TYPES = new Set(["int", "integer"]);
const FLOAT_TYPES = new Set(["float", "double", "number"]);
const OBJECT_TYPES = new Set(["dict", "object", "json", "list"]);

const normalizeType = (type: string): NormalizedType => {
  const lower = type.toLowerCase();
  if (BOOL_TYPES.has(lower)) return "boolean";
  if (INT_TYPES.has(lower)) return "numeric-int";
  if (FLOAT_TYPES.has(lower)) return "numeric-float";
  if (OBJECT_TYPES.has(lower)) return "object";
  return "text";
};

const coerceValue = (value: string, type: string): unknown => {
  switch (normalizeType(type)) {
    case "boolean":
      return value === "true";
    case "numeric-int":
    case "numeric-float": {
      const num = Number(value);
      return isNaN(num) ? value : num;
    }
    case "object":
      try {
        return JSON.parse(value);
      } catch {
        return value;
      }
    default:
      return value;
  }
};

type AgentRunnerInputFormProps = {
  fields: AgentParam[];
  onSubmit: (inputs: Record<string, unknown>) => void;
  isRunning: boolean;
  onValidityChange?: (hasAllRequired: boolean) => void;
};

const isFieldRequired = (field: AgentParam): boolean => {
  return field.presence !== "optional";
};

const buildSchema = (fields: AgentParam[]) => {
  const shape: Record<string, z.ZodTypeAny> = {};
  for (const field of fields) {
    // Boolean fields are always valid (Switch is always "true"/"false")
    if (normalizeType(field.type) === "boolean" || !isFieldRequired(field)) {
      shape[field.name] = z.string();
    } else {
      shape[field.name] = z.string().refine((v) => v.trim().length > 0, {
        message: "This field is required",
      });
    }
  }
  return z.object(shape);
};

const AgentRunnerInputForm: React.FC<AgentRunnerInputFormProps> = ({
  fields,
  onSubmit,
  isRunning,
  onValidityChange,
}) => {
  const schema = useMemo(() => buildSchema(fields), [fields]);

  const {
    register,
    handleSubmit,
    setValue,
    watch,
    formState: { errors, isValid },
  } = useForm({
    resolver: zodResolver(schema),
    defaultValues: fields.reduce(
      (acc, field) => {
        acc[field.name] = "";
        return acc;
      },
      {} as Record<string, string>,
    ),
    mode: "onChange",
  });

  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());
  const toggleCollapsed = (name: string) => {
    setCollapsed((prev) => {
      const next = new Set(prev);
      if (next.has(name)) next.delete(name);
      else next.add(name);
      return next;
    });
  };

  useEffect(() => {
    onValidityChange?.(isValid);
  }, [isValid, onValidityChange]);

  const onFormSubmit = handleSubmit((data) => {
    const inputs: Record<string, unknown> = {};
    for (const field of fields) {
      const value = data[field.name];
      if (!isFieldRequired(field) && value.trim() === "") {
        continue;
      }
      inputs[field.name] = coerceValue(value, field.type);
    }
    onSubmit(inputs);
  });

  return (
    <form id="agent-runner-form" onSubmit={onFormSubmit}>
      {fields.length === 0 ? (
        <div className="flex flex-col items-center py-8 text-muted-slate">
          <p className="comet-body-s">No input fields defined by this agent.</p>
        </div>
      ) : (
        <div className="flex flex-col gap-3">
          {fields.map((field) => {
            const normalized = normalizeType(field.type);
            const isOpen = !collapsed.has(field.name);
            return (
              <div
                key={field.name}
                className="overflow-hidden rounded-md border border-border bg-soft-background"
              >
                <div className="flex items-center gap-2 px-3 py-2">
                  <button
                    type="button"
                    aria-expanded={isOpen}
                    onClick={() => toggleCollapsed(field.name)}
                    className="flex shrink-0 items-center text-light-slate hover:text-foreground"
                  >
                    {isOpen ? (
                      <ChevronDown className="size-4" />
                    ) : (
                      <ChevronRight className="size-4" />
                    )}
                  </button>
                  <span className="comet-body-xs text-muted-slate">
                    {field.name}
                  </span>
                  <span className="text-[10px] text-light-slate opacity-60">
                    |
                  </span>
                  <span className="comet-body-xs font-mono text-light-slate">
                    {field.type}
                  </span>
                  {!isFieldRequired(field) && (
                    <span className="comet-body-xs text-muted-slate">
                      (optional)
                    </span>
                  )}
                </div>

                {isOpen && (
                  <div className="flex flex-col gap-1 px-3 pb-3">
                    {normalized === "boolean" ? (
                      <Switch
                        checked={watch(field.name) === "true"}
                        onCheckedChange={(checked) =>
                          setValue(field.name, String(checked))
                        }
                        disabled={isRunning}
                      />
                    ) : normalized === "object" ? (
                      <Textarea
                        {...register(field.name)}
                        placeholder={`Enter ${field.name}...`}
                        rows={4}
                        disabled={isRunning}
                      />
                    ) : (
                      <Input
                        {...register(field.name)}
                        placeholder={`Enter ${field.name}...`}
                        inputMode={
                          normalized === "numeric-int"
                            ? "numeric"
                            : normalized === "numeric-float"
                              ? "decimal"
                              : "text"
                        }
                        disabled={isRunning}
                      />
                    )}

                    {errors[field.name] && (
                      <span className="comet-body-xs text-destructive">
                        {errors[field.name]?.message as string}
                      </span>
                    )}
                  </div>
                )}
              </div>
            );
          })}
        </div>
      )}
    </form>
  );
};

export default AgentRunnerInputForm;
