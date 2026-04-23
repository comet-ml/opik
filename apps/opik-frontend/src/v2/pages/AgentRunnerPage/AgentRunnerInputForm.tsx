import React from "react";
import { useForm } from "react-hook-form";

import { Input } from "@/ui/input";
import { Textarea } from "@/ui/textarea";
import { Switch } from "@/ui/switch";
import { Label } from "@/ui/label";

type ParamPresence = "required" | "optional";

type AgentParam = {
  name: string;
  type: string;
  presence?: ParamPresence;
};

const NUMERIC_TYPES = new Set(["int", "integer", "float", "double", "number"]);
const BOOL_TYPES = new Set(["bool", "boolean"]);
const OBJECT_TYPES = new Set(["dict", "object", "json", "list"]);

const coerceValue = (value: string, type: string): unknown => {
  const lower = type.toLowerCase();

  if (BOOL_TYPES.has(lower)) {
    return value === "true";
  }

  if (NUMERIC_TYPES.has(lower)) {
    const num = Number(value);
    return isNaN(num) ? value : num;
  }

  if (OBJECT_TYPES.has(lower)) {
    try {
      return JSON.parse(value);
    } catch {
      return value;
    }
  }

  return value;
};

type AgentRunnerInputFormProps = {
  fields: AgentParam[];
  onSubmit: (inputs: Record<string, unknown>, maskId?: string) => void;
  isRunning: boolean;
};

const isFieldRequired = (field: AgentParam): boolean => {
  return field.presence !== "optional";
};

const AgentRunnerInputForm: React.FC<AgentRunnerInputFormProps> = ({
  fields,
  onSubmit,
  isRunning,
}) => {
  const {
    register,
    handleSubmit,
    setValue,
    watch,
    formState: { errors },
  } = useForm({
    defaultValues: fields.reduce(
      (acc, field) => {
        acc[field.name] = "";
        return acc;
      },
      {} as Record<string, string>,
    ),
  });

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
        <div className="flex flex-col gap-4">
          {fields.map((field) => (
            <div key={field.name} className="flex flex-col gap-1.5">
              <Label className="comet-body-xs-accented">
                {field.name}
                <span className="ml-1 font-normal text-light-slate">
                  {field.type}
                </span>
                {!isFieldRequired(field) && (
                  <span className="ml-1 font-normal text-muted-slate">
                    (optional)
                  </span>
                )}
              </Label>

              {field.type === "boolean" ? (
                <Switch
                  checked={watch(field.name) === "true"}
                  onCheckedChange={(checked) =>
                    setValue(field.name, String(checked))
                  }
                  disabled={isRunning}
                />
              ) : field.type === "object" || field.type === "json" ? (
                <Textarea
                  {...register(field.name, {
                    ...(isFieldRequired(field) && {
                      validate: (v: string) =>
                        v.trim().length > 0 || "This field is required",
                    }),
                  })}
                  placeholder={`Enter ${field.name}...`}
                  rows={4}
                  disabled={isRunning}
                />
              ) : (
                <Input
                  {...register(field.name, {
                    ...(isFieldRequired(field) && {
                      validate: (v: string) =>
                        v.trim().length > 0 || "This field is required",
                    }),
                  })}
                  placeholder={`Enter ${field.name}...`}
                  inputMode={
                    field.type === "integer" || field.type === "int"
                      ? "numeric"
                      : field.type === "float" || field.type === "double"
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
          ))}
        </div>
      )}
    </form>
  );
};

export default AgentRunnerInputForm;
