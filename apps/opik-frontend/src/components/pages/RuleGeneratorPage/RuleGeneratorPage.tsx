import React, { useCallback, useMemo, useRef, useState } from "react";
import { Check, Loader2, Play, RotateCcw, Square, X } from "lucide-react";
import useLocalStorageState from "use-local-storage-state";

import useAppStore from "@/store/AppStore";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import { Tabs, TabsList, TabsTrigger, TabsContent } from "@/components/ui/tabs";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import PromptModelSelect from "@/components/pages-shared/llm/PromptModelSelect/PromptModelSelect";
import useCompletionProxyStreaming from "@/api/playground/useCompletionProxyStreaming";
import {
  getDefaultConfigByProvider,
  parseCompletionOutput,
} from "@/lib/playground";
import {
  PROVIDER_MODEL_TYPE,
  PROVIDER_TYPE,
  COMPOSED_PROVIDER_TYPE,
  LLMPromptConfigsType,
} from "@/types/providers";
import { LLM_MESSAGE_ROLE } from "@/types/llm";
import { RULE_GENERATOR_SYSTEM_PROMPT } from "@/constants/rule-generator";
import RuleGeneratorInputsTable, {
  useRuleGeneratorInputs,
} from "./RuleGeneratorInputsTable";

const extractJson = (text: string): string => {
  const fenceMatch = text.match(/```(?:json)?\s*([\s\S]*?)```/);
  if (fenceMatch) {
    return fenceMatch[1].trim();
  }
  return text.trim();
};

const REQUIRED_FIELDS = [
  "scope",
  "evaluator_name",
  "judge_prompts",
  "score_schema",
  "expected_output_schema",
] as const;

const validateResult = (
  result: string,
): { isDefaultRule: boolean; isValidJson: boolean; missingFields: string[] } => {
  const trimmed = result.trim();
  if (!trimmed)
    return { isDefaultRule: false, isValidJson: false, missingFields: [] };
  if (trimmed === "OPIK_DEFAULT_RULE")
    return { isDefaultRule: true, isValidJson: false, missingFields: [] };
  try {
    const parsed = JSON.parse(trimmed);
    if (typeof parsed !== "object" || parsed === null) {
      return { isDefaultRule: false, isValidJson: false, missingFields: [] };
    }
    const missingFields = REQUIRED_FIELDS.filter((field) => !(field in parsed));
    return { isDefaultRule: false, isValidJson: true, missingFields };
  } catch {
    return { isDefaultRule: false, isValidJson: false, missingFields: [] };
  }
};

const formatJson = (text: string): string => {
  const cleaned = extractJson(text);
  try {
    return JSON.stringify(JSON.parse(cleaned), null, 2);
  } catch {
    return text;
  }
};

const RuleGeneratorPage: React.FC = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const DEFAULT_MODEL = PROVIDER_MODEL_TYPE.GPT_4_1_MINI;
  const DEFAULT_PROVIDER: COMPOSED_PROVIDER_TYPE = PROVIDER_TYPE.OPEN_AI;

  const [model, setModel] = useLocalStorageState<PROVIDER_MODEL_TYPE | "">(
    "opik_rule_generator_model",
    { defaultValue: DEFAULT_MODEL },
  );
  const [provider, setProvider] = useLocalStorageState<
    COMPOSED_PROVIDER_TYPE | ""
  >("opik_rule_generator_provider", { defaultValue: DEFAULT_PROVIDER });
  const [configs, setConfigs] = useLocalStorageState<LLMPromptConfigsType>(
    "opik_rule_generator_configs",
    {
      defaultValue: getDefaultConfigByProvider(DEFAULT_PROVIDER, DEFAULT_MODEL),
    },
  );
  const [systemPrompt, setSystemPrompt] = useLocalStorageState<string>(
    "opik_rule_generator_system_prompt",
    { defaultValue: RULE_GENERATOR_SYSTEM_PROMPT },
  );
  // Generator tab state
  const [scope, setScope] = useLocalStorageState<"trace" | "thread">(
    "opik_rule_generator_scope",
    { defaultValue: "trace" },
  );
  const [prompt, setPrompt] = useLocalStorageState<string>(
    "opik_rule_generator_prompt",
    { defaultValue: "" },
  );
  const [output, setOutput] = useLocalStorageState<string>(
    "opik_rule_generator_output",
    { defaultValue: "" },
  );
  const [isRunning, setIsRunning] = useState(false);
  const abortControllerRef = useRef<AbortController | null>(null);

  // Inputs tab state
  const [rows, setRows] = useRuleGeneratorInputs();
  const [isBatchRunning, setIsBatchRunning] = useState(false);
  const [batchProgress, setBatchProgress] = useState({ current: 0, total: 0 });
  const [loadingRowIds, setLoadingRowIds] = useState<Set<string>>(new Set());
  const [selectedRowIds, setSelectedRowIds] = useState<Set<string>>(new Set());
  const batchAbortRef = useRef<AbortController | null>(null);

  const BATCH_CONCURRENCY = 5;

  const runStreaming = useCompletionProxyStreaming({ workspaceName });

  const handleModelChange = useCallback(
    (newModel: PROVIDER_MODEL_TYPE, newProvider: COMPOSED_PROVIDER_TYPE) => {
      setModel(newModel);
      setProvider(newProvider);
      setConfigs(getDefaultConfigByProvider(newProvider, newModel));
    },
    [setConfigs, setModel, setProvider],
  );

  // --- Generator tab handlers ---

  const handleRun = useCallback(async () => {
    if (!model || !prompt.trim()) return;

    setIsRunning(true);
    setOutput("");

    const controller = new AbortController();
    abortControllerRef.current = controller;

    const run = await runStreaming({
      model,
      messages: [
        { role: LLM_MESSAGE_ROLE.system, content: systemPrompt },
        {
          role: LLM_MESSAGE_ROLE.user,
          content: `user_intent: ${prompt}

launch_context: scope is ${scope}`,
        },
      ],
      configs,
      signal: controller.signal,
      onAddChunk: (accumulated) => setOutput(accumulated),
    });

    setOutput(extractJson(parseCompletionOutput(run)));
    setIsRunning(false);
    abortControllerRef.current = null;
  }, [model, prompt, scope, systemPrompt, configs, runStreaming, setOutput]);

  const handleStop = useCallback(() => {
    abortControllerRef.current?.abort();
  }, []);

  const formattedOutput = useMemo(() => {
    if (!output) return "";
    return formatJson(output);
  }, [output]);

  const canRun = model !== "" && prompt.trim() !== "" && !isRunning;

  // --- Inputs tab handlers ---

  const processRow = useCallback(
    async (
      row: { id: string; userInput: string; inputType: string },
      signal: AbortSignal,
    ) => {
      setLoadingRowIds((prev) => new Set(prev).add(row.id));
      try {
        const run = await runStreaming({
          model: model as PROVIDER_MODEL_TYPE,
          messages: [
            { role: LLM_MESSAGE_ROLE.system, content: systemPrompt },
            {
              role: LLM_MESSAGE_ROLE.user,
              content: `user_intent: ${row.userInput}

launch_context: scope is ${row.inputType}`,
            },
          ],
          configs,
          signal,
          onAddChunk: () => {},
        });

        const result = formatJson(parseCompletionOutput(run));
        setRows((prev) =>
          prev.map((r) => (r.id === row.id ? { ...r, result } : r)),
        );
      } finally {
        setLoadingRowIds((prev) => {
          const next = new Set(prev);
          next.delete(row.id);
          return next;
        });
        setBatchProgress((prev) => ({
          ...prev,
          current: prev.current + 1,
        }));
      }
    },
    [model, systemPrompt, configs, runStreaming, setRows],
  );

  const handleBatchRun = useCallback(async () => {
    const inputRows = rows.filter((r) => r.userInput.trim());
    if (!model || inputRows.length === 0) return;

    setIsBatchRunning(true);
    setBatchProgress({ current: 0, total: inputRows.length });
    setLoadingRowIds(new Set(inputRows.map((r) => r.id)));

    const controller = new AbortController();
    batchAbortRef.current = controller;

    // Process in chunks of BATCH_CONCURRENCY
    for (let i = 0; i < inputRows.length; i += BATCH_CONCURRENCY) {
      if (controller.signal.aborted) break;

      const chunk = inputRows.slice(i, i + BATCH_CONCURRENCY);
      await Promise.allSettled(
        chunk.map((row) => processRow(row, controller.signal)),
      );
    }

    setIsBatchRunning(false);
    setLoadingRowIds(new Set());
    batchAbortRef.current = null;
  }, [model, rows, processRow, BATCH_CONCURRENCY]);

  const handleBatchRunSelected = useCallback(async () => {
    const inputRows = rows.filter(
      (r) => selectedRowIds.has(r.id) && r.userInput.trim(),
    );
    if (!model || inputRows.length === 0) return;

    setIsBatchRunning(true);
    setBatchProgress({ current: 0, total: inputRows.length });
    setLoadingRowIds(new Set(inputRows.map((r) => r.id)));

    const controller = new AbortController();
    batchAbortRef.current = controller;

    for (let i = 0; i < inputRows.length; i += BATCH_CONCURRENCY) {
      if (controller.signal.aborted) break;

      const chunk = inputRows.slice(i, i + BATCH_CONCURRENCY);
      await Promise.allSettled(
        chunk.map((row) => processRow(row, controller.signal)),
      );
    }

    setIsBatchRunning(false);
    setLoadingRowIds(new Set());
    batchAbortRef.current = null;
  }, [model, rows, selectedRowIds, processRow, BATCH_CONCURRENCY]);

  const handleBatchStop = useCallback(() => {
    batchAbortRef.current?.abort();
  }, []);

  const canBatchRun =
    model !== "" && rows.some((r) => r.userInput.trim()) && !isBatchRunning;

  const canBatchRunSelected =
    model !== "" &&
    selectedRowIds.size > 0 &&
    rows.some((r) => selectedRowIds.has(r.id) && r.userInput.trim()) &&
    !isBatchRunning;

  return (
    <div className="pt-6">
      <div className="mb-6 flex items-center justify-between">
        <h1 className="comet-title-l truncate break-words">Rule generator</h1>
      </div>

      <div className="flex gap-6">
        {/* Left sidebar: model picker + system prompt (30%) */}
        <div className="w-[30%] shrink-0">
          <div className="flex flex-col gap-4">
            <div>
              <PromptModelSelect
                value={model}
                provider={provider}
                workspaceName={workspaceName}
                onChange={handleModelChange}
              />
            </div>

            <div>
              <div className="mb-2 flex items-center justify-between">
                <h2 className="comet-title-s">System prompt</h2>
                {systemPrompt === RULE_GENERATOR_SYSTEM_PROMPT && (
                  <Button variant="link" size="sm">
                    <a
                      href="https://staging.dev.comet.com/opik/sasha-charts/optimizations/019c6c1e-99dd-72b2-af22-6e4c6a0a12fa/compare?optimizations=%5B%22019c7155-35ce-74d2-bbee-c38ba46d094f%22%5D"
                      target="_blank"
                      rel="noopener noreferrer"
                    >
                      Optimized prompt -{">"}
                    </a>
                  </Button>
                )}
                {systemPrompt !== RULE_GENERATOR_SYSTEM_PROMPT && (
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={() =>
                      setSystemPrompt(RULE_GENERATOR_SYSTEM_PROMPT)
                    }
                  >
                    <RotateCcw className="mr-1 size-3.5" />
                    Reset
                  </Button>
                )}
              </div>
              <Textarea
                value={systemPrompt}
                onChange={(e) => setSystemPrompt(e.target.value)}
                className="min-h-[70vh] font-mono text-sm"
              />
            </div>
          </div>
        </div>

        {/* Right content: tabs (remaining width) */}
        <div className="min-w-0 flex-1">
          <Tabs defaultValue="generator">
            <TabsList variant="underline">
              <TabsTrigger variant="underline" value="generator">
                Generator
              </TabsTrigger>
              <TabsTrigger variant="underline" value="inputs">
                Inputs
              </TabsTrigger>
            </TabsList>

            <TabsContent value="generator">
              <div className="flex flex-col gap-4">
                <div className="flex items-center gap-3">
                  {isRunning ? (
                    <Button variant="outline" onClick={handleStop}>
                      <Square className="mr-1 size-4" />
                      Stop
                    </Button>
                  ) : (
                    <Button onClick={handleRun} disabled={!canRun}>
                      <Play className="mr-1 size-4" />
                      Run
                    </Button>
                  )}
                  <Select
                    value={scope}
                    onValueChange={(v) => setScope(v as "trace" | "thread")}
                  >
                    <SelectTrigger className="w-[140px]">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="trace">Trace</SelectItem>
                      <SelectItem value="thread">Thread</SelectItem>
                    </SelectContent>
                  </Select>
                </div>

                <Textarea
                  placeholder="Describe the evaluation rule you want to create, e.g.: Create a hallucination check rule that scores output faithfulness as a double between 0 and 1"
                  value={prompt}
                  onChange={(e) => setPrompt(e.target.value)}
                  className="min-h-40 font-mono text-sm"
                />

                <div>
                  <h2 className="comet-title-s mb-2">Output</h2>
                  {output.trim() &&
                    !isRunning &&
                    (() => {
                      const { isDefaultRule, isValidJson, missingFields } =
                        validateResult(formattedOutput);
                      if (isDefaultRule) {
                        return (
                          <div className="my-3 flex items-center gap-1.5 text-xs">
                            <Check className="size-3.5 text-green-600" />
                            <span>Default rule (OPIK_DEFAULT_RULE)</span>
                          </div>
                        );
                      }
                      return (
                        <div className="my-3 flex items-center gap-4 text-xs">
                          <div className="flex items-center gap-1.5">
                            {isValidJson ? (
                              <Check className="size-3.5 text-green-600" />
                            ) : (
                              <X className="size-3.5 text-red-500" />
                            )}
                            <span>Valid JSON</span>
                          </div>
                          <div className="flex items-center gap-1.5">
                            {isValidJson && missingFields.length === 0 ? (
                              <Check className="size-3.5 text-green-600" />
                            ) : (
                              <X className="size-3.5 text-red-500" />
                            )}
                            <span>Required fields</span>
                            {isValidJson && missingFields.length > 0 && (
                              <span className="text-muted-slate">
                                (missing: {missingFields.join(", ")})
                              </span>
                            )}
                          </div>
                        </div>
                      );
                    })()}

                  <div className="min-h-40 rounded-md border border-input bg-muted p-4">
                    {isRunning && !output && (
                      <div className="flex items-center gap-2 text-muted-slate">
                        <Loader2 className="size-4 animate-spin" />
                        <span>Generating...</span>
                      </div>
                    )}
                    {output && (
                      <pre className="whitespace-pre-wrap break-words font-mono text-sm">
                        {formattedOutput}
                      </pre>
                    )}
                    {!isRunning && !output && (
                      <p className="text-muted-slate">
                        Describe an evaluation rule in natural language and
                        click Run to generate a JSON payload for the evaluators
                        API.
                      </p>
                    )}
                  </div>
                </div>
              </div>
            </TabsContent>

            <TabsContent value="inputs">
              <div className="mb-4 flex items-center gap-3">
                {isBatchRunning ? (
                  <Button variant="outline" onClick={handleBatchStop}>
                    <Square className="mr-1 size-4" />
                    Stop ({batchProgress.current}/{batchProgress.total})
                  </Button>
                ) : (
                  <>
                    <Button onClick={handleBatchRun} disabled={!canBatchRun}>
                      <Play className="mr-1 size-4" />
                      Run all
                    </Button>
                    {selectedRowIds.size > 0 && (
                      <Button
                        onClick={handleBatchRunSelected}
                        disabled={!canBatchRunSelected}
                        variant="outline"
                      >
                        <Play className="mr-1 size-4" />
                        Run selected ({selectedRowIds.size})
                      </Button>
                    )}
                  </>
                )}
              </div>
              <RuleGeneratorInputsTable
                loadingRowIds={loadingRowIds}
                selectedRowIds={selectedRowIds}
                onSelectionChange={setSelectedRowIds}
              />
            </TabsContent>
          </Tabs>
        </div>
      </div>
    </div>
  );
};

export default RuleGeneratorPage;
