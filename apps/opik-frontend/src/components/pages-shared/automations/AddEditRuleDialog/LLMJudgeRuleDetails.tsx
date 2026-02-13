import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import { UseFormReturn } from "react-hook-form";
import { ChevronDown, ChevronRight, Info } from "lucide-react";
import find from "lodash/find";
import get from "lodash/get";

import { Label } from "@/components/ui/label";
import {
  FormControl,
  FormField,
  FormItem,
  FormMessage,
} from "@/components/ui/form";
import PromptModelSelect from "@/components/pages-shared/llm/PromptModelSelect/PromptModelSelect";
import PromptModelConfigs from "@/components/pages-shared/llm/PromptModelSettings/PromptModelConfigs";
import SelectBox from "@/components/shared/SelectBox/SelectBox";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import LLMPromptMessages from "@/components/pages-shared/llm/LLMPromptMessages/LLMPromptMessages";
import LLMPromptMessagesVariables from "@/components/pages-shared/llm/LLMPromptMessagesVariables/LLMPromptMessagesVariables";
import LLMJudgeScores from "@/components/pages-shared/llm/LLMJudgeScores/LLMJudgeScores";
import {
  LLM_MESSAGE_ROLE_NAME_MAP,
  LLM_PROMPT_TEMPLATES,
} from "@/constants/llm";
import {
  LLM_JUDGE,
  LLM_MESSAGE_ROLE,
  LLMMessage,
  LLMPromptTemplate,
} from "@/types/llm";
import {
  generateDefaultLLMPromptMessage,
  getAllTemplateStringsFromContent,
} from "@/lib/llm";
import { COMPOSED_PROVIDER_TYPE, PROVIDER_MODEL_TYPE } from "@/types/providers";
import { safelyGetPromptMustacheTags } from "@/lib/prompt";
import { EvaluationRuleFormType } from "@/components/pages-shared/automations/AddEditRuleDialog/schema";
import useLLMProviderModelsData from "@/hooks/useLLMProviderModelsData";
import ExplainerIcon from "@/components/shared/ExplainerIcon/ExplainerIcon";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import { EVALUATORS_RULE_SCOPE } from "@/types/automations";
import { updateProviderConfig } from "@/lib/modelUtils";
import { TRACE_DATA_TYPE } from "@/hooks/useTracesOrSpansList";
import useTracesList from "@/api/traces/useTracesList";
import useSpansList from "@/api/traces/useSpansList";

const MESSAGE_TYPE_OPTIONS = [
  {
    label: LLM_MESSAGE_ROLE_NAME_MAP[LLM_MESSAGE_ROLE.system],
    value: LLM_MESSAGE_ROLE.system,
  },
  {
    label: LLM_MESSAGE_ROLE_NAME_MAP[LLM_MESSAGE_ROLE.user],
    value: LLM_MESSAGE_ROLE.user,
  },
  {
    label: LLM_MESSAGE_ROLE_NAME_MAP[LLM_MESSAGE_ROLE.ai],
    value: LLM_MESSAGE_ROLE.ai,
  },
  {
    label: LLM_MESSAGE_ROLE_NAME_MAP[LLM_MESSAGE_ROLE.tool_execution_result],
    value: LLM_MESSAGE_ROLE.tool_execution_result,
  },
];

// Helper function to extract paths from an object
const extractPaths = (
  obj: unknown,
  prefix: string = "",
  maxDepth: number = 3,
  currentDepth: number = 0,
): string[] => {
  if (currentDepth >= maxDepth || obj === null || obj === undefined) {
    return [];
  }

  if (typeof obj !== "object" || Array.isArray(obj)) {
    return [];
  }

  const paths: string[] = [];
  for (const key of Object.keys(obj as Record<string, unknown>)) {
    const fullPath = prefix ? `${prefix}.${key}` : key;
    paths.push(fullPath);

    const value = (obj as Record<string, unknown>)[key];
    if (value && typeof value === "object" && !Array.isArray(value)) {
      paths.push(...extractPaths(value, fullPath, maxDepth, currentDepth + 1));
    }
  }
  return paths;
};

// Helper function to get a preview of a value
const getValuePreview = (value: unknown, maxLength: number = 50): string => {
  if (value === null) return "null";
  if (value === undefined) return "undefined";
  if (typeof value === "string") {
    return value.length > maxLength
      ? `"${value.substring(0, maxLength)}..."`
      : `"${value}"`;
  }
  if (typeof value === "number" || typeof value === "boolean") {
    return String(value);
  }
  if (Array.isArray(value)) {
    return `[Array(${value.length})]`;
  }
  if (typeof value === "object") {
    return "{...}";
  }
  return String(value);
};

// Component to display trace structure
const TraceStructureHelper: React.FC<{
  projectId: string;
  isSpanScope?: boolean;
}> = ({ projectId, isSpanScope = false }) => {
  const [isOpen, setIsOpen] = useState(false);
  const [selectedTraceIndex, setSelectedTraceIndex] = useState(0);
  const [testPath, setTestPath] = useState("");
  const [testResult, setTestResult] = useState<{
    value: unknown;
    error: string | null;
  } | null>(null);

  const fetchEnabled = Boolean(projectId) && isOpen;

  const { data: tracesData, isLoading: isTracesLoading } = useTracesList(
    {
      projectId,
      page: 1,
      size: 5,
      truncate: true,
    },
    {
      enabled: fetchEnabled && !isSpanScope,
    },
  );

  const { data: spansData, isLoading: isSpansLoading } = useSpansList(
    {
      projectId,
      page: 1,
      size: 5,
      truncate: true,
    },
    {
      enabled: fetchEnabled && isSpanScope,
    },
  );

  const isLoading = isSpanScope ? isSpansLoading : isTracesLoading;
  const items = isSpanScope
    ? spansData?.content || []
    : tracesData?.content || [];
  const itemLabel = isSpanScope ? "span" : "trace";

  useEffect(() => {
    setSelectedTraceIndex((prev) =>
      items.length === 0 ? 0 : Math.min(prev, items.length - 1),
    );
  }, [projectId, items.length]);

  const selectedTrace = items[selectedTraceIndex];

  const inputPaths = useMemo(
    () =>
      selectedTrace?.input ? extractPaths(selectedTrace.input, "input") : [],
    [selectedTrace?.input],
  );

  const outputPaths = useMemo(
    () =>
      selectedTrace?.output ? extractPaths(selectedTrace.output, "output") : [],
    [selectedTrace?.output],
  );

  const metadataPaths = useMemo(
    () =>
      selectedTrace?.metadata
        ? extractPaths(selectedTrace.metadata, "metadata")
        : [],
    [selectedTrace?.metadata],
  );

  const getValueAtPath = useCallback(
    (path: string): { value: unknown; error: string | null } => {
      if (!selectedTrace) {
        return { value: undefined, error: "No trace selected" };
      }

      if (!path || path.trim() === "") {
        return { value: undefined, error: "Empty path" };
      }

      const parts = path.trim().split(".");
      const section = parts[0];

      // Validate section
      if (!["input", "output", "metadata"].includes(section)) {
        return {
          value: undefined,
          error: `Invalid section "${section}". Must be one of: input, output, metadata`,
        };
      }

      const sectionKey = section as "input" | "output" | "metadata";
      const restPath = parts.slice(1);

      // Handle root section (e.g., just "input")
      if (restPath.length === 0) {
        const sectionValue = selectedTrace[sectionKey];
        if (sectionValue === undefined || sectionValue === null) {
          return { value: undefined, error: `Section "${section}" is empty` };
        }
        return { value: sectionValue, error: null };
      }

      let current: unknown = selectedTrace[sectionKey];

      for (let i = 0; i < restPath.length; i++) {
        const part = restPath[i];

        if (current === null || current === undefined) {
          const traversedPath = [section, ...restPath.slice(0, i)].join(".");
          return {
            value: undefined,
            error: `Path "${traversedPath}" is null/undefined, cannot access "${part}"`,
          };
        }

        if (typeof current !== "object") {
          const traversedPath = [section, ...restPath.slice(0, i)].join(".");
          return {
            value: undefined,
            error: `Path "${traversedPath}" is not an object (got ${typeof current}), cannot access "${part}"`,
          };
        }

        if (Array.isArray(current)) {
          const index = parseInt(part, 10);
          if (isNaN(index)) {
            return {
              value: undefined,
              error: `Cannot access "${part}" on array. Use numeric index.`,
            };
          }
          current = current[index];
        } else {
          current = (current as Record<string, unknown>)[part];
        }
      }

      return { value: current, error: null };
    },
    [selectedTrace],
  );

  // Test the path when user types
  const handleTestPath = useCallback(() => {
    if (!testPath.trim()) {
      setTestResult(null);
      return;
    }
    const result = getValueAtPath(testPath);
    setTestResult(result);
  }, [testPath, getValueAtPath]);

  // Format value for display
  const formatResultValue = (value: unknown): string => {
    if (value === undefined) return "undefined";
    if (value === null) return "null";
    try {
      return JSON.stringify(value, null, 2);
    } catch {
      return String(value);
    }
  };

  return (
    <div className="rounded-md border border-border bg-muted/30 p-4">
      <div className="flex items-center gap-2 mb-2">
        <Info className="size-4 text-muted-foreground" />
        <Label className="text-sm font-medium">Direct JSONPath Mode</Label>
      </div>
      <p className="text-sm text-muted-foreground mb-3">
        Use dot-notation directly in your template variables (e.g.,{" "}
        <code className="bg-muted px-1 rounded">{"{{input.question}}"}</code>).
        The backend will resolve paths automatically.
      </p>

      <button
        type="button"
        onClick={() => setIsOpen(!isOpen)}
        className="flex items-center gap-1 text-sm font-medium text-primary hover:underline"
      >
        {isOpen ? (
          <ChevronDown className="size-4" />
        ) : (
          <ChevronRight className="size-4" />
        )}
        View {itemLabel} structure from project
      </button>

      {isOpen && (
        <div className="mt-3">
          {!projectId ? (
            <p className="text-sm text-muted-foreground">
              Select a project to view {itemLabel} structure.
            </p>
          ) : isLoading ? (
            <p className="text-sm text-muted-foreground">
              Loading {itemLabel}s...
            </p>
          ) : items.length === 0 ? (
            <p className="text-sm text-muted-foreground">
              No {itemLabel}s found in this project.
            </p>
          ) : (
            <div className="space-y-3">
              {items.length > 1 && (
                <div className="flex items-center gap-2">
                  <Label className="text-xs">Sample {itemLabel}:</Label>
                  <select
                    className="text-xs border rounded px-2 py-1 bg-background"
                    value={selectedTraceIndex}
                    onChange={(e) =>
                      setSelectedTraceIndex(Number(e.target.value))
                    }
                  >
                    {items.map((item, idx) => (
                      <option key={item.id} value={idx}>
                        {item.name || item.id.substring(0, 8)}
                      </option>
                    ))}
                  </select>
                </div>
              )}

              {/* Path Tester */}
              <div className="bg-background rounded p-3 border">
                <Label className="text-xs font-medium mb-2 block">
                  Test a path
                </Label>
                <div className="flex gap-2">
                  <input
                    type="text"
                    className="flex-1 text-sm border rounded px-2 py-1 bg-background font-mono"
                    placeholder="e.g., input.question or output.answer"
                    value={testPath}
                    onChange={(e) => setTestPath(e.target.value)}
                    onKeyDown={(e) => {
                      if (e.key === "Enter") {
                        e.preventDefault();
                        handleTestPath();
                      }
                    }}
                  />
                  <button
                    type="button"
                    onClick={handleTestPath}
                    className="px-3 py-1 text-sm bg-primary text-primary-foreground rounded hover:bg-primary/90"
                  >
                    Test
                  </button>
                </div>
                {testResult && (
                  <div className="mt-2">
                    {testResult.error ? (
                      <div className="text-xs text-destructive bg-destructive/10 rounded p-2">
                        Error: {testResult.error}
                      </div>
                    ) : (
                      <div className="text-xs">
                        <div className="text-muted-foreground mb-1">
                          Result for{" "}
                          <code className="bg-muted px-1 rounded">
                            {`{{${testPath}}}`}
                          </code>
                          :
                        </div>
                        <pre className="bg-muted rounded p-2 overflow-auto max-h-40 text-foreground">
                          {formatResultValue(testResult.value)}
                        </pre>
                      </div>
                    )}
                  </div>
                )}
              </div>

              <div className="text-xs font-mono bg-background rounded p-3 border max-h-60 overflow-auto">
                {/* Input section */}
                <div className="mb-3">
                  <div className="font-semibold text-blue-600 mb-1">
                    input (use as {"{{input}}"} for entire object)
                  </div>
                  {inputPaths.length > 0 ? (
                    <div className="pl-2 space-y-0.5">
                      {inputPaths.map((path) => {
                        const result = getValueAtPath(path);
                        return (
                          <button
                            type="button"
                            key={path}
                            className="flex gap-2 w-full text-left hover:bg-muted/50 rounded px-1 -mx-1"
                            onClick={() => {
                              setTestPath(path);
                              setTestResult(result);
                            }}
                          >
                            <span className="text-blue-600">{path}</span>
                            <span className="text-muted-foreground truncate">
                              → {getValuePreview(result.value)}
                            </span>
                          </button>
                        );
                      })}
                    </div>
                  ) : (
                    <div className="pl-2 text-muted-foreground">
                      (empty or no data)
                    </div>
                  )}
                </div>

                {/* Output section */}
                <div className="mb-3">
                  <div className="font-semibold text-green-600 mb-1">
                    output (use as {"{{output}}"} for entire object)
                  </div>
                  {outputPaths.length > 0 ? (
                    <div className="pl-2 space-y-0.5">
                      {outputPaths.map((path) => {
                        const result = getValueAtPath(path);
                        return (
                          <button
                            type="button"
                            key={path}
                            className="flex gap-2 w-full text-left hover:bg-muted/50 rounded px-1 -mx-1"
                            onClick={() => {
                              setTestPath(path);
                              setTestResult(result);
                            }}
                          >
                            <span className="text-green-600">{path}</span>
                            <span className="text-muted-foreground truncate">
                              → {getValuePreview(result.value)}
                            </span>
                          </button>
                        );
                      })}
                    </div>
                  ) : (
                    <div className="pl-2 text-muted-foreground">
                      (empty or no data)
                    </div>
                  )}
                </div>

                {/* Metadata section */}
                <div>
                  <div className="font-semibold text-purple-600 mb-1">
                    metadata (use as {"{{metadata}}"} for entire object)
                  </div>
                  {metadataPaths.length > 0 ? (
                    <div className="pl-2 space-y-0.5">
                      {metadataPaths.map((path) => {
                        const result = getValueAtPath(path);
                        return (
                          <button
                            type="button"
                            key={path}
                            className="flex gap-2 w-full text-left hover:bg-muted/50 rounded px-1 -mx-1"
                            onClick={() => {
                              setTestPath(path);
                              setTestResult(result);
                            }}
                          >
                            <span className="text-purple-600">{path}</span>
                            <span className="text-muted-foreground truncate">
                              → {getValuePreview(result.value)}
                            </span>
                          </button>
                        );
                      })}
                    </div>
                  ) : (
                    <div className="pl-2 text-muted-foreground">
                      (empty or no data)
                    </div>
                  )}
                </div>
              </div>

              <p className="text-xs text-muted-foreground">
                Click any path above to test it, or type your own path in the
                tester.
              </p>
            </div>
          )}
        </div>
      )}
    </div>
  );
};

type LLMJudgeRuleDetailsProps = {
  workspaceName: string;
  form: UseFormReturn<EvaluationRuleFormType>;
  projectName?: string;
  datasetColumnNames?: string[];
  hideVariables?: boolean;
};

const LLMJudgeRuleDetails: React.FC<LLMJudgeRuleDetailsProps> = ({
  workspaceName,
  form,
  projectName,
  datasetColumnNames,
  hideVariables = false,
}) => {
  const cache = useRef<Record<string | LLM_JUDGE, LLMPromptTemplate>>({});
  const { calculateModelProvider, calculateDefaultModel } =
    useLLMProviderModelsData();

  const scope = form.watch("scope");
  const isThreadScope = scope === EVALUATORS_RULE_SCOPE.thread;
  const isSpanScope = scope === EVALUATORS_RULE_SCOPE.span;

  const templates = LLM_PROMPT_TEMPLATES[scope];

  // Clear variables when hideVariables is enabled (direct JSONPath mode)
  useEffect(() => {
    if (hideVariables) {
      form.setValue("llmJudgeDetails.variables", {});
      form.setValue("llmJudgeDetails.parsingVariablesError", false);
    }
  }, [hideVariables, form]);

  // Determine the type for autocomplete based on scope
  const autocompleteType = isSpanScope
    ? TRACE_DATA_TYPE.spans
    : TRACE_DATA_TYPE.traces;

  const handleAddProvider = useCallback(
    (provider: COMPOSED_PROVIDER_TYPE) => {
      const model =
        (form.watch("llmJudgeDetails.model") as PROVIDER_MODEL_TYPE) || "";

      if (!model) {
        form.setValue(
          "llmJudgeDetails.model",
          calculateDefaultModel(model, [provider], provider),
        );
      }
    },
    [calculateDefaultModel, form],
  );

  const handleDeleteProvider = useCallback(
    (provider: COMPOSED_PROVIDER_TYPE) => {
      const model =
        (form.watch("llmJudgeDetails.model") as PROVIDER_MODEL_TYPE) || "";
      const currentProvider = calculateModelProvider(model);
      if (currentProvider === provider) {
        form.setValue("llmJudgeDetails.model", "");
      }
    },
    [calculateModelProvider, form],
  );

  // Memoized callback to handle messages change
  const handleMessagesChange = useCallback(
    (
      messages: LLMMessage[],
      fieldOnChange: (messages: LLMMessage[]) => void,
      formInstance: UseFormReturn<EvaluationRuleFormType>,
      skipVariableExtraction: boolean = false,
    ) => {
      fieldOnChange(messages);

      // When direct JSONPath mode is enabled (hideVariables=true), skip variable extraction
      // Variables in templates like {{input.question}} will be resolved directly by backend
      if (skipVariableExtraction) {
        formInstance.setValue("llmJudgeDetails.variables", {});
        formInstance.setValue("llmJudgeDetails.parsingVariablesError", false);
        return;
      }

      // recalculate variables
      const variables = formInstance.getValues("llmJudgeDetails.variables");
      const localVariables: Record<string, string> = {};
      let parsingVariablesError: boolean = false;
      messages
        .reduce<string[]>((acc, m) => {
          // Extract template strings from both text and image URLs
          const templateStrings = getAllTemplateStringsFromContent(m.content);
          // Get mustache tags from all template strings
          const allTags = templateStrings.flatMap((str) => {
            const tags = safelyGetPromptMustacheTags(str);
            if (!tags) {
              parsingVariablesError = true;
              return [];
            }
            return tags;
          });
          return acc.concat(allTags);
        }, [])
        .filter((v) => v !== "")
        .forEach((v: string) => (localVariables[v] = variables[v] ?? ""));

      formInstance.setValue("llmJudgeDetails.variables", localVariables);
      formInstance.setValue(
        "llmJudgeDetails.parsingVariablesError",
        parsingVariablesError,
      );
    },
    [],
  );

  return (
    <>
      <FormField
        control={form.control}
        name="llmJudgeDetails.model"
        render={({ field, formState }) => {
          const model = field.value as PROVIDER_MODEL_TYPE | "";
          const provider = calculateModelProvider(model);
          const validationErrors = get(formState.errors, [
            "llmJudgeDetails",
            "model",
          ]);

          return (
            <FormItem>
              <Label>Model</Label>
              <FormControl>
                <div className="flex h-10 items-center justify-center gap-2">
                  <PromptModelSelect
                    value={model}
                    onChange={(m) => {
                      if (m) {
                        field.onChange(m);
                        // Update config to ensure reasoning models have temperature >= 1.0
                        const newProvider = calculateModelProvider(m);
                        const currentConfig = form.getValues(
                          "llmJudgeDetails.config",
                        );
                        const adjustedConfig = updateProviderConfig(
                          currentConfig,
                          { model: m, provider: newProvider },
                        );
                        if (
                          adjustedConfig &&
                          adjustedConfig !== currentConfig
                        ) {
                          form.setValue(
                            "llmJudgeDetails.config",
                            adjustedConfig,
                          );
                        }
                      }
                    }}
                    provider={provider}
                    hasError={Boolean(validationErrors?.message)}
                    workspaceName={workspaceName}
                    onAddProvider={handleAddProvider}
                    onDeleteProvider={handleDeleteProvider}
                  />

                  <FormField
                    control={form.control}
                    name="llmJudgeDetails.config"
                    render={({ field }) => (
                      <PromptModelConfigs
                        size="icon"
                        provider={provider}
                        model={model}
                        configs={field.value}
                        onChange={(partialConfig) => {
                          field.onChange({ ...field.value, ...partialConfig });
                        }}
                      />
                    )}
                  ></FormField>
                </div>
              </FormControl>
              <FormMessage />
            </FormItem>
          );
        }}
      />
      <FormField
        control={form.control}
        name="llmJudgeDetails.template"
        render={({ field }) => (
          <FormItem>
            <Label>
              Prompt{" "}
              <ExplainerIcon
                className="inline"
                {...EXPLAINERS_MAP[EXPLAINER_ID.whats_that_prompt_select]}
              />
            </Label>
            <FormControl>
              <SelectBox
                value={field.value}
                onChange={(newTemplate: string) => {
                  const { variables, messages, schema, template } =
                    form.getValues("llmJudgeDetails");
                  if (newTemplate !== template) {
                    cache.current[template] = {
                      ...cache.current[template],
                      messages: messages,
                      variables: variables,
                      schema: schema,
                    };

                    const templateData =
                      cache.current[newTemplate] ??
                      find(templates, (t) => t.value === newTemplate);

                    form.setValue(
                      "llmJudgeDetails.messages",
                      templateData.messages,
                    );
                    form.setValue(
                      "llmJudgeDetails.variables",
                      templateData.variables ?? {},
                    );
                    form.setValue(
                      "llmJudgeDetails.schema",
                      templateData.schema,
                    );
                    form.setValue(
                      "llmJudgeDetails.template",
                      newTemplate as LLM_JUDGE,
                    );
                  }
                }}
                options={templates}
              />
            </FormControl>
            <FormMessage />
          </FormItem>
        )}
      />
      <div className="-mt-2 flex flex-col gap-2">
        <FormField
          control={form.control}
          name="llmJudgeDetails.messages"
          render={({ field, formState }) => {
            const messages = field.value;
            const validationErrors = get(formState.errors, [
              "llmJudgeDetails",
              "messages",
            ]);

            return (
              <>
                <LLMPromptMessages
                  messages={messages}
                  validationErrors={validationErrors}
                  possibleTypes={MESSAGE_TYPE_OPTIONS}
                  disableMedia={isThreadScope}
                  promptVariables={datasetColumnNames}
                  onChange={(messages: LLMMessage[]) =>
                    handleMessagesChange(
                      messages,
                      field.onChange,
                      form,
                      hideVariables,
                    )
                  }
                  onAddMessage={() =>
                    field.onChange([
                      ...messages,
                      generateDefaultLLMPromptMessage({
                        role: LLM_MESSAGE_ROLE.user,
                      }),
                    ])
                  }
                />
              </>
            );
          }}
        />
        {!isThreadScope && !hideVariables && (
          <FormField
            control={form.control}
            name="llmJudgeDetails.variables"
            render={({ field, formState }) => {
              const parsingVariablesError = form.getValues(
                "llmJudgeDetails.parsingVariablesError",
              );
              const validationErrors = get(formState.errors, [
                "llmJudgeDetails",
                "variables",
              ]);

              return (
                <>
                  <LLMPromptMessagesVariables
                    parsingError={parsingVariablesError}
                    validationErrors={validationErrors}
                    projectId={form.watch("projectIds")[0] || ""}
                    variables={field.value}
                    onChange={field.onChange}
                    projectName={projectName}
                    datasetColumnNames={datasetColumnNames}
                    type={autocompleteType}
                    includeIntermediateNodes
                  />
                </>
              );
            }}
          />
        )}
        {!isThreadScope && hideVariables && (
          <TraceStructureHelper
            projectId={form.watch("projectIds")[0] || ""}
            isSpanScope={isSpanScope}
          />
        )}
      </div>
      <div className="flex flex-col gap-2">
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
        <FormField
          control={form.control}
          name="llmJudgeDetails.schema"
          render={({ field, formState }) => {
            const validationErrors = get(formState.errors, [
              "llmJudgeDetails",
              "schema",
            ]);

            return (
              <LLMJudgeScores
                validationErrors={validationErrors}
                scores={field.value}
                onChange={field.onChange}
              />
            );
          }}
        />
      </div>
    </>
  );
};

export default LLMJudgeRuleDetails;
