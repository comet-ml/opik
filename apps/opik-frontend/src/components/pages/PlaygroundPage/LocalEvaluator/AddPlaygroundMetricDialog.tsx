import React, { useCallback, useEffect, useMemo } from "react";
import { z } from "zod";
import { zodResolver } from "@hookform/resolvers/zod";
import { useForm } from "react-hook-form";
import get from "lodash/get";
import { v4 as uuidv4 } from "uuid";
import { Info } from "lucide-react";

import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogAutoScrollBody,
  DialogClose,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Label } from "@/components/ui/label";
import {
  Form,
  FormControl,
  FormField,
  FormItem,
  FormMessage,
} from "@/components/ui/form";
import { Input } from "@/components/ui/input";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Description } from "@/components/ui/description";
import { Switch } from "@/components/ui/switch";
import { Separator } from "@/components/ui/separator";
import { Tag } from "@/components/ui/tag";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import TracesOrSpansPathsAutocomplete from "@/components/pages-shared/traces/TracesOrSpansPathsAutocomplete/TracesOrSpansPathsAutocomplete";
import {
  PlaygroundMetricConfig,
  LocalMetricDescriptor,
  LocalMetricParam,
} from "@/types/local-evaluator";

// Dynamic schema based on metric params
const createFormSchema = (selectedMetric: LocalMetricDescriptor | undefined) => {
  // Build init_args schema based on metric's init_params
  const initArgsSchema: Record<string, z.ZodTypeAny> = {};
  const argumentsSchema: Record<string, z.ZodTypeAny> = {};

  if (selectedMetric) {
    // For init params - all optional, use appropriate type based on param type
    selectedMetric.init_params.forEach((param) => {
      initArgsSchema[param.name] = z.any().optional();
    });

    // For score params - required params need non-empty strings
    selectedMetric.score_params.forEach((param) => {
      if (param.required) {
        argumentsSchema[param.name] = z
          .string()
          .min(1, { message: "Field path is required" })
          .regex(/^(input|output|metadata)/, {
            message: `Path must start with "input", "output", or "metadata"`,
          });
      } else {
        argumentsSchema[param.name] = z.string().optional();
      }
    });
  }

  return z.object({
    metric_name: z.string().min(1, { message: "Metric is required" }),
    init_args: z.object(initArgsSchema).optional().default({}),
    arguments: z.object(argumentsSchema),
  });
};

type FormType = {
  metric_name: string;
  init_args: Record<string, unknown>;
  arguments: Record<string, string>;
};

interface AddPlaygroundMetricDialogProps {
  open: boolean;
  setOpen: (open: boolean) => void;
  metric?: PlaygroundMetricConfig;
  metrics: LocalMetricDescriptor[];
  onSave: (metric: PlaygroundMetricConfig) => void;
  projectId?: string;
  projectName?: string;
  datasetColumnNames?: string[];
}

const TRACE_ROOT_KEYS = ["input", "output", "metadata"] as const;

const formatParamType = (type: string | null): string => {
  if (!type) return "any";
  // Simplify complex types
  if (type.startsWith("Union")) return "Union";
  if (type.startsWith("Optional")) return "Optional";
  if (type.startsWith("List")) return "List";
  return type;
};

const formatDefaultValue = (value: unknown): string => {
  if (value === null) return "null";
  if (value === undefined) return "undefined";
  if (typeof value === "string") return `"${value}"`;
  if (typeof value === "boolean") return value ? "true" : "false";
  if (typeof value === "number") return String(value);
  return JSON.stringify(value);
};

const ParamInput: React.FC<{
  param: LocalMetricParam;
  value: unknown;
  onChange: (value: unknown) => void;
  error?: string;
}> = ({ param, value, onChange, error }) => {
  const type = param.type?.toLowerCase() || "";

  // Boolean type - use switch
  if (type === "bool" || type === "boolean") {
    return (
      <div className="flex items-center gap-2">
        <Switch
          checked={Boolean(value ?? param.default)}
          onCheckedChange={onChange}
        />
        {param.default !== null && param.default !== undefined && (
          <span className="text-xs text-muted-slate">
            Default: {param.default ? "true" : "false"}
          </span>
        )}
      </div>
    );
  }

  // Number types
  if (type === "int" || type === "float" || type === "number") {
    return (
      <Input
        type="number"
        step={type === "float" ? "0.1" : "1"}
        value={value !== undefined ? String(value) : ""}
        placeholder={
          param.default !== null && param.default !== undefined
            ? `Default: ${param.default}`
            : "Optional"
        }
        onChange={(e) => {
          const val = e.target.value;
          if (val === "") {
            onChange(undefined);
          } else {
            onChange(type === "int" ? parseInt(val) : parseFloat(val));
          }
        }}
        className={cn({ "border-destructive": Boolean(error) })}
      />
    );
  }

  // String and other types - use text input
  return (
    <Input
      value={value !== undefined ? String(value) : ""}
      placeholder={
        param.default !== null && param.default !== undefined
          ? `Default: ${formatDefaultValue(param.default)}`
          : "Optional"
      }
      onChange={(e) => {
        const val = e.target.value;
        onChange(val === "" ? undefined : val);
      }}
      className={cn({ "border-destructive": Boolean(error) })}
    />
  );
};

const AddPlaygroundMetricDialog: React.FC<AddPlaygroundMetricDialogProps> = ({
  open,
  setOpen,
  metric,
  metrics,
  onSave,
  projectId,
  projectName,
  datasetColumnNames,
}) => {
  const isEdit = Boolean(metric);
  const title = isEdit ? "Edit metric" : "Add metric";
  const submitText = isEdit ? "Update" : "Add metric";

  const form = useForm<FormType>({
    defaultValues: {
      metric_name: metric?.metric_name || "",
      init_args: metric?.init_args || {},
      arguments: metric?.arguments || {},
    },
  });

  const selectedMetricName = form.watch("metric_name");

  const selectedMetric = useMemo(() => {
    return metrics.find((m) => m.name === selectedMetricName);
  }, [metrics, selectedMetricName]);

  // Create dynamic resolver when metric changes
  const formSchema = useMemo(
    () => createFormSchema(selectedMetric),
    [selectedMetric],
  );

  // Update form resolver when schema changes
  useEffect(() => {
    if (selectedMetric) {
      // Re-validate with new schema
      form.trigger();
    }
  }, [selectedMetric, form]);

  // Update arguments when metric changes
  useEffect(() => {
    if (selectedMetric && !isEdit) {
      const newArguments: Record<string, string> = {};
      selectedMetric.score_params.forEach((param) => {
        const existingValue = form.getValues(`arguments.${param.name}`);
        newArguments[param.name] = existingValue || "";
      });
      form.setValue("arguments", newArguments);

      // Initialize init_args with defaults
      const newInitArgs: Record<string, unknown> = {};
      selectedMetric.init_params.forEach((param) => {
        const existingValue = form.getValues(`init_args.${param.name}`);
        if (existingValue !== undefined) {
          newInitArgs[param.name] = existingValue;
        }
        // Don't set defaults - let the backend use them
      });
      form.setValue("init_args", newInitArgs);
    }
  }, [selectedMetric, form, isEdit]);

  // Reset form when dialog opens
  useEffect(() => {
    if (open) {
      form.reset({
        metric_name: metric?.metric_name || "",
        init_args: metric?.init_args || {},
        arguments: metric?.arguments || {},
      });
    }
  }, [open, metric, form]);

  const handleSubmit = useCallback(
    (data: FormType) => {
      // Validate with dynamic schema
      const result = formSchema.safeParse(data);
      if (!result.success) {
        // Set form errors
        result.error.errors.forEach((err) => {
          form.setError(err.path.join(".") as keyof FormType, {
            message: err.message,
          });
        });
        return;
      }

      // Clean up init_args - remove undefined values
      const cleanedInitArgs: Record<string, unknown> = {};
      Object.entries(data.init_args || {}).forEach(([key, value]) => {
        if (value !== undefined && value !== "") {
          cleanedInitArgs[key] = value;
        }
      });

      // Clean up arguments - remove empty optional values
      const cleanedArguments: Record<string, string> = {};
      Object.entries(data.arguments || {}).forEach(([key, value]) => {
        if (value && value.trim() !== "") {
          cleanedArguments[key] = value;
        }
      });

      const newMetric: PlaygroundMetricConfig = {
        id: metric?.id || uuidv4(),
        metric_name: data.metric_name,
        init_args: cleanedInitArgs,
        arguments: cleanedArguments,
      };
      onSave(newMetric);
      setOpen(false);
    },
    [metric, onSave, setOpen, formSchema, form],
  );

  // Filter init params to show only user-configurable ones
  const configurableInitParams = useMemo(() => {
    if (!selectedMetric) return [];
    // Hide internal params like 'name', 'track', 'project_name'
    return selectedMetric.init_params.filter(
      (p) => !["name", "track", "project_name"].includes(p.name),
    );
  }, [selectedMetric]);

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[700px]">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
        </DialogHeader>
        <DialogAutoScrollBody>
          <Form {...form}>
            <form
              className="flex flex-col gap-4"
              onSubmit={form.handleSubmit(handleSubmit)}
            >
              {/* Metric Selection */}
              <FormField
                control={form.control}
                name="metric_name"
                render={({ field, formState }) => {
                  const validationErrors = get(formState.errors, [
                    "metric_name",
                  ]);
                  return (
                    <FormItem>
                      <Label>Metric</Label>
                      <FormControl>
                        <Select
                          value={field.value}
                          onValueChange={field.onChange}
                          disabled={isEdit}
                        >
                          <SelectTrigger
                            className={cn({
                              "border-destructive": Boolean(
                                validationErrors?.message,
                              ),
                            })}
                          >
                            <SelectValue placeholder="Select a metric" />
                          </SelectTrigger>
                          <SelectContent>
                            {metrics.map((m) => (
                              <SelectItem key={m.name} value={m.name}>
                                <div className="flex flex-col">
                                  <span>{m.name}</span>
                                </div>
                              </SelectItem>
                            ))}
                          </SelectContent>
                        </Select>
                      </FormControl>
                      <FormMessage />
                    </FormItem>
                  );
                }}
              />

              {/* Metric Description */}
              {selectedMetric && (
                <div className="rounded-md border bg-muted/50 p-3">
                  <Description className="text-sm">
                    {selectedMetric.description}
                  </Description>
                </div>
              )}

              {/* Configuration Sections - only show when metric is selected */}
              {selectedMetric && (
                <>
                  <Separator />

                  {/* Init Parameters Section */}
                  {configurableInitParams.length > 0 && (
                    <div className="flex flex-col gap-3">
                      <div className="flex items-center gap-2">
                        <Label className="text-base font-semibold">
                          Initialization Parameters
                        </Label>
                        <TooltipWrapper content="These parameters configure how the metric is initialized. Leave empty to use defaults.">
                          <Info className="size-4 text-muted-slate" />
                        </TooltipWrapper>
                      </div>
                      <Description className="text-sm">
                        Configure how the metric is initialized. All fields are
                        optional.
                      </Description>
                      <div className="flex flex-col gap-4 rounded-md border p-3">
                        {configurableInitParams.map((param) => (
                          <FormField
                            key={param.name}
                            control={form.control}
                            name={`init_args.${param.name}` as const}
                            render={({ field }) => (
                              <FormItem className="flex flex-col gap-1">
                                <div className="flex items-center gap-2">
                                  <TooltipWrapper
                                    content={param.description || undefined}
                                  >
                                    <Tag
                                      variant={
                                        param.required ? "green" : "gray"
                                      }
                                      size="sm"
                                      className="cursor-help"
                                    >
                                      {param.name}
                                    </Tag>
                                  </TooltipWrapper>
                                  <span className="text-xs text-muted-slate">
                                    {formatParamType(param.type)}
                                  </span>
                                  {!param.required && (
                                    <span className="text-xs text-muted-slate">
                                      (optional)
                                    </span>
                                  )}
                                </div>
                                {param.description && (
                                  <Description className="text-xs">
                                    {param.description}
                                  </Description>
                                )}
                                <FormControl>
                                  <ParamInput
                                    param={param}
                                    value={field.value}
                                    onChange={field.onChange}
                                  />
                                </FormControl>
                              </FormItem>
                            )}
                          />
                        ))}
                      </div>
                    </div>
                  )}

                  {/* Score Parameters Section (Variable Mapping) */}
                  {selectedMetric.score_params.length > 0 && (
                    <div className="flex flex-col gap-3">
                      <div className="flex items-center gap-2">
                        <Label className="text-base font-semibold">
                          Score Parameters
                        </Label>
                        <TooltipWrapper content="Map metric arguments to trace fields. Required fields must be mapped.">
                          <Info className="size-4 text-muted-slate" />
                        </TooltipWrapper>
                      </div>
                      <Description className="text-sm">
                        Map each metric argument to a trace field. Use paths
                        like &apos;input.messages&apos;,
                        &apos;output.output&apos;, or
                        &apos;metadata.context&apos;.
                      </Description>
                      <div className="flex flex-col gap-4 rounded-md border p-3">
                        {selectedMetric.score_params.map((param) => (
                          <FormField
                            key={param.name}
                            control={form.control}
                            name={`arguments.${param.name}` as const}
                            render={({ field, formState }) => {
                              const error = get(formState.errors, [
                                "arguments",
                                param.name,
                              ]);
                              return (
                                <FormItem className="flex flex-col gap-1">
                                  <div className="flex items-center gap-2">
                                    <TooltipWrapper
                                      content={param.description || undefined}
                                    >
                                      <Tag
                                        variant={
                                          param.required ? "green" : "gray"
                                        }
                                        size="sm"
                                        className="cursor-help"
                                      >
                                        {param.name}
                                      </Tag>
                                    </TooltipWrapper>
                                    <span className="text-xs text-muted-slate">
                                      {formatParamType(param.type)}
                                    </span>
                                    {param.required ? (
                                      <span className="text-xs text-destructive">
                                        *
                                      </span>
                                    ) : (
                                      <span className="text-xs text-muted-slate">
                                        (optional)
                                      </span>
                                    )}
                                  </div>
                                  {param.description && (
                                    <Description className="text-xs">
                                      {param.description}
                                    </Description>
                                  )}
                                  <FormControl>
                                    <TracesOrSpansPathsAutocomplete
                                      projectId={projectId || ""}
                                      rootKeys={[...TRACE_ROOT_KEYS]}
                                      value={field.value || ""}
                                      hasError={Boolean(error)}
                                      onValueChange={field.onChange}
                                      projectName={projectName}
                                      datasetColumnNames={datasetColumnNames}
                                    />
                                  </FormControl>
                                  {error && (
                                    <p className="text-xs text-destructive">
                                      {error.message as string}
                                    </p>
                                  )}
                                </FormItem>
                              );
                            }}
                          />
                        ))}
                      </div>
                    </div>
                  )}
                </>
              )}
            </form>
          </Form>
        </DialogAutoScrollBody>
        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline">Cancel</Button>
          </DialogClose>
          <Button type="submit" onClick={form.handleSubmit(handleSubmit)}>
            {submitText}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default AddPlaygroundMetricDialog;

