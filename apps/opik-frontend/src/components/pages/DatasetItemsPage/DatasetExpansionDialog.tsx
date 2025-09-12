import React, { useCallback, useState, useMemo } from "react";
import { Bot, Info } from "lucide-react";

import useDatasetExpansionMutation from "@/api/datasets/useDatasetExpansionMutation";
import useDatasetItemsList from "@/api/datasets/useDatasetItemsList";
import useAppStore from "@/store/AppStore";
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
import { Textarea } from "@/components/ui/textarea";
import { Input } from "@/components/ui/input";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/components/ui/accordion";
import Loader from "@/components/shared/Loader/Loader";
import PromptModelSelect from "@/components/pages-shared/llm/PromptModelSelect/PromptModelSelect";
import { DatasetExpansionRequest, DatasetItem } from "@/types/datasets";
import { PROVIDER_MODEL_TYPE, PROVIDER_TYPE } from "@/types/providers";
import { Tag } from "@/components/ui/tag";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

type DatasetExpansionDialogProps = {
  datasetId: string;
  open: boolean;
  setOpen: (open: boolean) => void;
  onSamplesGenerated?: (samples: DatasetItem[]) => void;
};

const DatasetExpansionDialog: React.FunctionComponent<
  DatasetExpansionDialogProps
> = ({ datasetId: initialDatasetId, open, setOpen, onSamplesGenerated }) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const [selectedModel, setSelectedModel] = useState<PROVIDER_MODEL_TYPE | "">(
    PROVIDER_MODEL_TYPE.GPT_4O,
  );
  const [selectedProvider, setSelectedProvider] = useState<PROVIDER_TYPE | "">(
    PROVIDER_TYPE.OPEN_AI,
  );
  const [sampleCount, setSampleCount] = useState<number>(5);
  const [variationInstructions, setVariationInstructions] =
    useState<string>("");
  const [preserveFields, setPreserveFields] = useState<string[]>([]);
  const [customPrompt, setCustomPrompt] = useState<string>("");
  const [isEditingPrompt, setIsEditingPrompt] = useState<boolean>(false);

  const { mutate, isPending } = useDatasetExpansionMutation();

  // Fetch dataset items to analyze structure automatically
  const { data: sampleData, isLoading: isAnalyzing } = useDatasetItemsList(
    {
      datasetId: initialDatasetId || "",
      page: 1,
      size: 50, // Analyze up to 50 items for better pattern detection
    },
    {
      enabled: !!initialDatasetId && open,
    },
  );

  // Analyze dataset structure from sample data
  const datasetAnalysis = useMemo(() => {
    if (!sampleData?.content?.length) return null;

    const fields = new Set<string>();
    const fieldTypes: Record<string, Set<string>> = {};
    const fieldFrequency: Record<string, number> = {};

    sampleData.content.forEach((item) => {
      Object.keys(item.data).forEach((key) => {
        fields.add(key);
        fieldFrequency[key] = (fieldFrequency[key] || 0) + 1;
        if (!fieldTypes[key]) fieldTypes[key] = new Set();

        const value = (item.data as Record<string, unknown>)[key];
        if (typeof value === "object" && value !== null) {
          fieldTypes[key].add("object");
        } else {
          fieldTypes[key].add(typeof value);
        }
      });
    });

    // Only include fields that appear in at least 80% of samples
    const totalSamples = sampleData.content.length;
    const commonFields = Array.from(fields).filter(
      (field) => fieldFrequency[field] >= totalSamples * 0.8,
    );

    return {
      totalFields: fields.size,
      commonFields,
      allFields: Array.from(fields),
      fieldTypes: Object.fromEntries(
        Object.entries(fieldTypes).map(([field, types]) => [
          field,
          Array.from(types),
        ]),
      ),
      fieldFrequency,
      sampleCount: sampleData.content.length,
    };
  }, [sampleData?.content]);

  // Generate default prompt
  const defaultPrompt = useMemo(() => {
    if (!sampleData?.content?.length) return "";

    const exampleJsons = sampleData.content
      .slice(0, 3)
      .map((item) => JSON.stringify(item.data, null, 2));

    let prompt = `You are a synthetic data generator for machine learning datasets. Generate ${sampleCount} new dataset samples that follow the same JSON structure and patterns as the examples provided.\n\nEXAMPLES:\n`;

    exampleJsons.forEach((example, i) => {
      prompt += `Example ${i + 1}:\n${example}\n\n`;
    });

    prompt += `REQUIREMENTS:\n- Generate exactly ${sampleCount} samples\n- Maintain the exact same JSON structure as the examples\n- Create realistic and diverse variations of the data\n- Return ONLY a JSON array of the generated samples, no additional text\n`;

    if (preserveFields.length > 0) {
      prompt += `- Keep these fields consistent with patterns from examples: ${preserveFields.join(
        ", ",
      )}\n`;
    }

    if (variationInstructions?.trim()) {
      prompt += `- Additional instructions: ${variationInstructions}\n`;
    }

    prompt += "\nGenerate the samples now:";
    return prompt;
  }, [sampleData?.content, sampleCount, preserveFields, variationInstructions]);

  // Use custom prompt if editing, otherwise use default
  const activePrompt = isEditingPrompt ? customPrompt : defaultPrompt;

  // Update custom prompt when default changes (but only if not currently editing)
  React.useEffect(() => {
    if (!isEditingPrompt && defaultPrompt !== customPrompt) {
      setCustomPrompt(defaultPrompt);
    }
  }, [defaultPrompt, isEditingPrompt, customPrompt]);

  // Auto-populate preserve fields when analysis is available
  React.useEffect(() => {
    if (datasetAnalysis?.commonFields && preserveFields.length === 0) {
      setPreserveFields(datasetAnalysis.commonFields);
    }
  }, [datasetAnalysis?.commonFields, preserveFields.length]);

  const handleFieldToggle = useCallback((field: string) => {
    setPreserveFields((prev) =>
      prev.includes(field) ? prev.filter((f) => f !== field) : [...prev, field],
    );
  }, []);

  const handleSubmit = useCallback(() => {
    if (!selectedModel || !initialDatasetId) return;

    const requestData: DatasetExpansionRequest = {
      model: selectedModel,
      sample_count: sampleCount,
      preserve_fields: preserveFields.length > 0 ? preserveFields : undefined,
      variation_instructions: variationInstructions?.trim() || undefined,
      custom_prompt:
        isEditingPrompt || customPrompt !== defaultPrompt
          ? activePrompt
          : undefined,
    };

    mutate(
      { datasetId: initialDatasetId, ...requestData },
      {
        onSuccess: (response) => {
          onSamplesGenerated?.(response.generated_samples);
          setOpen(false);
          // Reset form
          setSelectedModel(PROVIDER_MODEL_TYPE.GPT_4O);
          setSampleCount(5);
          setVariationInstructions("");
          setPreserveFields([]);
        },
      },
    );
  }, [
    initialDatasetId,
    selectedModel,
    sampleCount,
    preserveFields,
    variationInstructions,
    activePrompt,
    defaultPrompt,
    isEditingPrompt,
    customPrompt,
    mutate,
    onSamplesGenerated,
    setOpen,
  ]);

  const handleModelChange = useCallback(
    (model: PROVIDER_MODEL_TYPE, provider: PROVIDER_TYPE) => {
      setSelectedModel(model);
      setSelectedProvider(provider);
    },
    [],
  );

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <Bot className="size-4" />
            Expand Dataset with AI
            <TooltipWrapper
              content="This will generate synthetic samples based on your existing data patterns. The generated samples will be available for review before adding to your dataset."
              side="bottom"
            >
              <Info className="size-4 cursor-help text-muted-foreground" />
            </TooltipWrapper>
          </DialogTitle>
        </DialogHeader>
        <DialogAutoScrollBody className="flex flex-col gap-4">
          {/* Dataset Structure Analysis */}
          {isAnalyzing && (
            <div className="flex items-center gap-2 rounded-md border bg-muted/30 p-3">
              <Loader className="size-4" />
              <span className="text-sm text-muted-foreground">
                Analyzing dataset structure...
              </span>
            </div>
          )}

          {datasetAnalysis && (
            <div className="space-y-3 rounded-md border bg-muted/20 p-3">
              <div className="flex items-center justify-between">
                <h4 className="text-sm font-medium">Detected Structure</h4>
                <div className="flex items-center gap-2 text-xs text-muted-foreground">
                  <span>Analyzed {datasetAnalysis.sampleCount} samples</span>
                  <Tag variant="gray" size="sm">
                    {datasetAnalysis.totalFields} fields
                  </Tag>
                </div>
              </div>

              <div className="space-y-2">
                <Label className="text-xs font-medium text-muted-foreground">
                  Fields to preserve:
                </Label>
                <div className="flex flex-wrap gap-1">
                  {datasetAnalysis.allFields.map((field) => {
                    const isCommon =
                      datasetAnalysis.commonFields.includes(field);
                    const isSelected = preserveFields.includes(field);
                    const frequency = Math.round(
                      (datasetAnalysis.fieldFrequency[field] /
                        datasetAnalysis.sampleCount) *
                        100,
                    );

                    return (
                      <TooltipWrapper
                        key={field}
                        content={
                          <div className="space-y-1 text-xs">
                            <div>Appears in {frequency}% of samples</div>
                            <div>
                              Type:{" "}
                              {datasetAnalysis.fieldTypes[field]?.join(" | ")}
                            </div>
                            <div className="text-muted-foreground">
                              {isCommon
                                ? "Auto-selected (common field)"
                                : "Click to toggle"}
                            </div>
                          </div>
                        }
                      >
                        <Tag
                          variant={isSelected ? "blue" : "gray"}
                          size="sm"
                          className={`cursor-pointer text-xs transition-colors ${
                            isSelected
                              ? "bg-primary text-primary-foreground"
                              : "hover:bg-muted-foreground/10"
                          }`}
                          onClick={() => handleFieldToggle(field)}
                        >
                          {field}
                          {isCommon && (
                            <span className="ml-1 opacity-70">✓</span>
                          )}
                        </Tag>
                      </TooltipWrapper>
                    );
                  })}
                </div>
                <p className="text-xs text-muted-foreground">
                  Selected fields will be preserved. Common fields (≥80%) are
                  auto-selected.
                </p>
              </div>
            </div>
          )}

          <div className="space-y-2">
            <Label htmlFor="model">Model</Label>
            <PromptModelSelect
              value={selectedModel}
              workspaceName={workspaceName}
              onChange={handleModelChange}
              provider={selectedProvider}
            />
          </div>

          <div className="space-y-2">
            <Label htmlFor="sample-count">Number of samples</Label>
            <div className="flex items-center gap-3">
              <Input
                id="sample-count"
                type="number"
                min={1}
                max={200}
                value={sampleCount}
                onChange={(e) => {
                  const value = e.target.value;
                  if (value === "") {
                    setSampleCount(5);
                  } else {
                    const num = parseInt(value, 10);
                    if (!isNaN(num) && num >= 1 && num <= 200) {
                      setSampleCount(num);
                    }
                  }
                }}
                className="w-24"
              />
              <span className="text-sm text-muted-foreground">(1-200)</span>
            </div>
          </div>

          <div className="space-y-2">
            <Label htmlFor="variation-instructions">
              Additional instructions (optional)
            </Label>
            <Textarea
              id="variation-instructions"
              value={variationInstructions}
              onChange={(e) => setVariationInstructions(e.target.value)}
              placeholder="e.g., Create variations that test edge cases, focus on different user personas"
              rows={2}
              className="resize-none text-sm"
            />
          </div>

          {/* Prompt Preview */}
          {defaultPrompt && (
            <Accordion type="single" collapsible className="w-full">
              <AccordionItem
                value="prompt-preview"
                className="rounded-md border"
              >
                <AccordionTrigger className="px-3 py-2 text-sm hover:no-underline">
                  <div className="flex w-full items-center justify-between pr-2">
                    <span className="text-sm font-medium">
                      Preview generation prompt
                    </span>
                    <span
                      onClick={(e) => {
                        e.stopPropagation();
                        if (isEditingPrompt) {
                          setIsEditingPrompt(false);
                        } else {
                          setCustomPrompt(activePrompt);
                          setIsEditingPrompt(true);
                        }
                      }}
                      className="flex h-6 cursor-pointer items-center rounded bg-secondary px-2 text-xs hover:bg-secondary/80"
                    >
                      {isEditingPrompt ? "Save" : "Edit"}
                    </span>
                  </div>
                </AccordionTrigger>
                <AccordionContent className="px-3 pb-3">
                  {isEditingPrompt ? (
                    <div className="space-y-2">
                      <Textarea
                        value={customPrompt}
                        onChange={(e) => setCustomPrompt(e.target.value)}
                        rows={10}
                        className="resize-none font-mono text-xs"
                        placeholder="Enter your custom prompt..."
                      />
                      <div className="flex gap-2">
                        <Button
                          size="sm"
                          onClick={() => setIsEditingPrompt(false)}
                          className="h-auto px-2 py-1 text-xs"
                        >
                          Apply
                        </Button>
                        <Button
                          variant="outline"
                          size="sm"
                          onClick={() => {
                            setCustomPrompt(defaultPrompt);
                            setIsEditingPrompt(false);
                          }}
                          className="h-auto px-2 py-1 text-xs"
                        >
                          Reset
                        </Button>
                      </div>
                    </div>
                  ) : (
                    <div className="rounded-md border bg-muted/20 p-3">
                      <pre className="whitespace-pre-wrap font-mono text-xs text-muted-foreground">
                        {activePrompt}
                      </pre>
                    </div>
                  )}
                </AccordionContent>
              </AccordionItem>
            </Accordion>
          )}
        </DialogAutoScrollBody>
        <DialogFooter className="gap-2">
          <DialogClose asChild>
            <Button variant="outline" size="sm" disabled={isPending}>
              Cancel
            </Button>
          </DialogClose>
          <Button
            onClick={handleSubmit}
            disabled={
              !selectedModel || !initialDatasetId || isAnalyzing || isPending
            }
            size="sm"
          >
            {isPending && <Loader className="mr-2 size-4" />}
            {isPending ? "Generating..." : "Generate Samples"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default DatasetExpansionDialog;
