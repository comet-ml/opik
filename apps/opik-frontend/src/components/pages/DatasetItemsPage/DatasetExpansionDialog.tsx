import React, { useCallback, useState, useMemo } from "react";

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
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { AlertTriangle, XCircle, Info } from "lucide-react";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/components/ui/accordion";
import PromptModelSelect from "@/components/pages-shared/llm/PromptModelSelect/PromptModelSelect";
import { DatasetExpansionRequest, DatasetItem } from "@/types/datasets";
import { PROVIDER_MODEL_TYPE, PROVIDER_TYPE } from "@/types/providers";
import { Tag } from "@/components/ui/tag";
import SyntaxHighlighter from "@/components/shared/SyntaxHighlighter/SyntaxHighlighter";
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
    () => {
      const saved = localStorage.getItem("opik-dataset-expansion-model");
      return saved ? (saved as PROVIDER_MODEL_TYPE) : "";
    },
  );
  const [selectedProvider, setSelectedProvider] = useState<PROVIDER_TYPE | "">(
    () => {
      const saved = localStorage.getItem("opik-dataset-expansion-provider");
      return saved ? (saved as PROVIDER_TYPE) : "";
    },
  );
  const [sampleCount, setSampleCount] = useState<number>(5);
  const [variationInstructions, setVariationInstructions] =
    useState<string>("");
  const [preserveFields, setPreserveFields] = useState<string[]>([]);
  const [customPrompt, setCustomPrompt] = useState<string>("");
  const [hasUserEditedPrompt, setHasUserEditedPrompt] = useState<boolean>(false);
  const [validationError, setValidationError] = useState<string | null>(null);
  const [generationProgress, setGenerationProgress] = useState<number>(0);
  const [progressMessage, setProgressMessage] = useState<string>("");
  const [showAllFields, setShowAllFields] = useState<boolean>(false);

  const { mutate, isPending, error, isError } = useDatasetExpansionMutation();

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

  // Initialize and update custom prompt with default prompt
  React.useEffect(() => {
    if (defaultPrompt) {
      if (!customPrompt || !hasUserEditedPrompt) {
        // Set custom prompt to default if it's empty or user hasn't manually edited it
        setCustomPrompt(defaultPrompt);
      }
    }
  }, [defaultPrompt, customPrompt, hasUserEditedPrompt]);

  // Auto-populate preserve fields when analysis is available
  React.useEffect(() => {
    if (datasetAnalysis?.commonFields && preserveFields.length === 0) {
      setPreserveFields(datasetAnalysis.commonFields);
    }
  }, [datasetAnalysis?.commonFields, preserveFields.length]);


  const handleSubmit = useCallback(() => {
    if (!selectedModel || !initialDatasetId) return;

    // Reset any previous validation errors
    setValidationError(null);

    // Client-side validation
    const sampleCountNumber = typeof sampleCount === "string" ? parseInt(sampleCount, 10) : sampleCount;
    if (isNaN(sampleCountNumber) || sampleCountNumber < 1 || sampleCountNumber > 200) {
      setValidationError("Sample count must be between 1 and 200.");
      return;
    }

    if (customPrompt && customPrompt.trim().length < 10) {
      setValidationError("Custom prompt must be at least 10 characters long.");
      return;
    }

    if (!sampleData?.content?.length) {
      setValidationError(
        "Dataset analysis is still in progress. Please wait for it to complete.",
      );
      return;
    }

    const requestData: DatasetExpansionRequest = {
      model: selectedModel,
      sample_count: sampleCountNumber,
      preserve_fields: preserveFields.length > 0 ? preserveFields : undefined,
      variation_instructions: variationInstructions?.trim() || undefined,
      custom_prompt: hasUserEditedPrompt ? customPrompt : undefined,
    };

    // Start progress simulation
    setGenerationProgress(0);
    setProgressMessage("Initializing AI generation...");

    const progressInterval = setInterval(() => {
      setGenerationProgress((prev) => {
        const next = prev + Math.random() * 15;
        if (next > 90) return 90; // Don't complete until actual response

        // Update progress messages
        if (next > 20 && next <= 40) {
          setProgressMessage("Analyzing dataset patterns...");
        } else if (next > 40 && next <= 70) {
          setProgressMessage("Generating synthetic samples...");
        } else if (next > 70) {
          setProgressMessage("Finalizing generated data...");
        }

        return next;
      });
    }, 800);

    mutate(
      { datasetId: initialDatasetId, ...requestData },
      {
        onSuccess: (response) => {
          clearInterval(progressInterval);
          setGenerationProgress(100);
          setProgressMessage("Generation completed successfully!");

          // Small delay to show completion before closing
          setTimeout(() => {
            onSamplesGenerated?.(response.generated_samples);
            setOpen(false);
            // Reset form and progress (keep model selection for next time)
            setSampleCount(5);
            setVariationInstructions("");
            setPreserveFields([]);
            setValidationError(null);
            setGenerationProgress(0);
            setProgressMessage("");
          }, 1000);
        },
        onError: () => {
          clearInterval(progressInterval);
          setGenerationProgress(0);
          setProgressMessage("");
          // Error handling is managed by the mutation itself via toast
        },
      },
    );
  }, [
    initialDatasetId,
    selectedModel,
    sampleCount,
    preserveFields,
    variationInstructions,
    customPrompt,
    defaultPrompt,
    mutate,
    onSamplesGenerated,
    setOpen,
    sampleData?.content?.length,
  ]);

  const handleModelChange = useCallback(
    (model: PROVIDER_MODEL_TYPE, provider: PROVIDER_TYPE) => {
      setSelectedModel(model);
      setSelectedProvider(provider);
      localStorage.setItem("opik-dataset-expansion-model", model);
      localStorage.setItem("opik-dataset-expansion-provider", provider);
    },
    [],
  );

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle className="comet-title-s">
            Expand dataset with AI
          </DialogTitle>
          <p className="comet-body-s my-4 text-muted-foreground">
            This will generate synthetic samples based on your existing data
            patterns. The generated samples will be available for review before
            adding to your dataset.
          </p>
        </DialogHeader>
        <DialogAutoScrollBody className="flex flex-col gap-4">
          {/* Error Display */}
          {(validationError || isError) && (
            <Alert variant="destructive" size="sm">
              <XCircle className="size-4" />
              <AlertTitle>Generation Error</AlertTitle>
              <AlertDescription>
                {validationError ||
                  (error as Error)?.message ||
                  "An error occurred while generating samples. Please try again."}
              </AlertDescription>
            </Alert>
          )}
          {/* Dataset Structure Analysis - Enhanced Loading State */}
          {isAnalyzing && (
            <div className="space-y-3">
              <div className="flex items-center gap-3 rounded-lg border bg-gradient-to-r from-primary/5 to-primary/10 p-4">
                <Spinner size="small" className="text-primary" />
                <div className="flex-1">
                  <div className="text-sm font-medium text-primary">
                    Analyzing dataset structure
                  </div>
                  <div className="text-xs text-muted-foreground">
                    Analyzing dataset structure and field patterns
                  </div>
                </div>
              </div>

              {/* Loading skeletons for the fields that will appear */}
              <div className="space-y-2">
                <Skeleton className="h-4 w-32" />
                <div className="flex items-center gap-2">
                  <Skeleton className="h-8 w-full" />
                  <div className="flex items-center gap-2">
                    <Skeleton className="h-4 w-16" />
                    <Skeleton className="h-5 w-12" />
                  </div>
                </div>
                <Skeleton className="h-3 w-3/4" />
              </div>
            </div>
          )}

          {/* Empty Dataset State */}
          {!isAnalyzing &&
            sampleData?.content &&
            sampleData.content.length === 0 && (
              <Alert variant="callout" size="sm">
                <AlertTriangle className="size-4" />
                <AlertTitle>No dataset samples found</AlertTitle>
                <AlertDescription>
                  This dataset appears to be empty. Add some sample data to your
                  dataset first before trying to expand it with AI.
                </AlertDescription>
              </Alert>
            )}

          {datasetAnalysis?.allFields && datasetAnalysis.allFields.length > 0 && (
            <div className="space-y-4">
              <div className="rounded-lg border-2 border-dashed border-muted-foreground/20 bg-muted/10 p-4">
                <div className="mb-3 flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <div className="rounded-md bg-blue-100 p-1.5 dark:bg-blue-900/20">
                      <svg
                        className="size-4 text-blue-600 dark:text-blue-400"
                        viewBox="0 0 24 24"
                        fill="none"
                        stroke="currentColor"
                        strokeWidth="2"
                      >
                        <path d="M9 12l2 2 4-4" />
                        <path d="M21 12c.552 0 1-.448 1-1s-.448-1-1-1-1 .448-1 1 .448 1 1 1z" />
                        <path d="M3 12c.552 0 1-.448 1-1s-.448-1-1-1-1 .448-1 1 .448 1 1 1z" />
                        <path d="M12 21c.552 0 1-.448 1-1s-.448-1-1-1-1 .448-1 1 .448 1 1 1z" />
                        <path d="M12 3c.552 0 1-.448 1-1s-.448-1-1-1-1 .448-1 1 .448 1 1 1z" />
                      </svg>
                    </div>
                    <div>
                      <div className="flex items-center gap-2">
                        <Label htmlFor="fields" className="text-sm font-medium">
                          Detected dataset structure
                        </Label>
                        <TooltipWrapper
                          content="Choose which fields to maintain consistency for"
                          side="top"
                        >
                          <Info className="size-3.5 text-muted-foreground cursor-help" />
                        </TooltipWrapper>
                      </div>
                    </div>
                  </div>
                  <div className="text-right">
                    <div className="text-xs text-muted-foreground">
                      Analyzed samples
                    </div>
                    <div className="text-sm font-medium">
                      {datasetAnalysis?.sampleCount}
                    </div>
                  </div>
                </div>

                <div className="space-y-3">
                  <div className="flex flex-wrap gap-2">
                    {datasetAnalysis.allFields
                      .slice(0, showAllFields ? undefined : 20)
                      .map((field) => {
                        const isSelected = preserveFields.includes(field);
                        const frequency = datasetAnalysis.fieldFrequency[field];
                        const totalSamples = datasetAnalysis.sampleCount;
                        const percentage = Math.round((frequency / totalSamples) * 100);
                        
                        return (
                          <Tag
                            key={field}
                            variant={isSelected ? "primary" : "gray"}
                            className="cursor-pointer transition-colors hover:bg-primary-hover/10"
                            onClick={() => {
                              setPreserveFields(prev => 
                                isSelected 
                                  ? prev.filter(f => f !== field)
                                  : [...prev, field]
                              );
                            }}
                          >
                            <span className="font-medium">{field}</span>
                            <span className="ml-1 text-xs opacity-70">({percentage}%)</span>
                          </Tag>
                        );
                      })}
                  </div>
                  
                  {datasetAnalysis.allFields.length > 20 && (
                    <div className="flex items-center justify-between pt-2">
                      <Button
                        variant="ghost"
                        size="sm"
                        onClick={() => setShowAllFields(!showAllFields)}
                        className="text-xs"
                      >
                        {showAllFields ? (
                          <>Show less ({datasetAnalysis.allFields.length - 20} hidden)</>
                        ) : (
                          <>Show all ({datasetAnalysis.allFields.length - 20} more)</>
                        )}
                      </Button>
                      <div className="text-xs text-muted-foreground">
                        {preserveFields.length} of {datasetAnalysis.allFields.length} fields selected
                      </div>
                    </div>
                  )}

                  <div className="rounded-md bg-amber-50 p-3 text-xs dark:bg-amber-900/20">
                    <div className="flex items-start gap-2">
                      <svg
                        className="mt-0.5 size-3 shrink-0 text-amber-600 dark:text-amber-400"
                        viewBox="0 0 24 24"
                        fill="none"
                        stroke="currentColor"
                        strokeWidth="2"
                      >
                        <path d="M12 9v3.75m-9.303 3.376c-.866 1.5.217 3.374 1.948 3.374h14.71c1.73 0 2.813-1.874 1.948-3.374L13.949 3.378c-.866-1.5-3.032-1.5-3.898 0L2.697 16.126zM12 15.75h.007v.008H12v-.008z" />
                      </svg>
                      <div className="text-amber-800 dark:text-amber-200">
                        <div className="font-medium">
                          Field preservation tips:
                        </div>
                        <div className="mt-1 space-y-1">
                          <div>
                            • Fields appearing in ≥80% of samples are
                            auto-selected
                          </div>
                          <div>
                            • Selected fields will maintain similar patterns in
                            generated data
                          </div>
                        </div>
                      </div>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          )}

          <div className="mt-6 space-y-2">
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
            <Input
              id="sample-count"
              type="number"
              min={1}
              max={200}
              value={sampleCount}
              onChange={(e) => {
                const value = e.target.value;
                // Clear validation error when user starts typing
                if (validationError) setValidationError(null);

                // Allow empty input for editing - store empty string temporarily
                if (value === "") {
                  setSampleCount("" as any); // Temporarily allow empty string
                  return;
                }

                const num = parseInt(value, 10);
                if (!isNaN(num)) {
                  setSampleCount(num);
                }
              }}
              className="w-full"
            />
            <p className="comet-body-s text-muted-foreground">Range 1-200</p>
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

          {/* Enhanced Prompt Preview and Editing */}
          {defaultPrompt && (
            <Accordion type="single" collapsible className="mt-6 w-full">
              <AccordionItem
                value="prompt-preview"
                className="rounded-lg border-2 transition-colors hover:border-primary/20"
              >
                <AccordionTrigger className="px-4 py-3 text-sm hover:no-underline">
                  <div className="flex w-full items-center gap-3">
                    <div className="rounded-md bg-primary/10 p-1">
                      <svg
                        className="size-4 text-primary"
                        viewBox="0 0 24 24"
                        fill="none"
                        stroke="currentColor"
                        strokeWidth="2"
                      >
                        <path d="M14.5 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7.5L14.5 2z" />
                        <polyline points="14,2 14,8 20,8" />
                        <line x1="16" y1="13" x2="8" y2="13" />
                        <line x1="16" y1="17" x2="8" y2="17" />
                        <polyline points="10,9 9,9 8,9" />
                      </svg>
                    </div>
                    <div>
                      <div className="text-left text-sm font-medium">
                        Generation prompt
                      </div>
                      <div className="text-left text-xs text-muted-foreground">
                        Customize the prompt sent to the AI model
                      </div>
                    </div>
                  </div>
                </AccordionTrigger>
                <AccordionContent className="px-4 pb-4">
                  <div className="space-y-3">
                    <div className="text-xs text-muted-foreground">
                      Customize the prompt that will be sent to the AI model.
                      Use clear instructions for best results.
                    </div>
                    <Textarea
                      value={customPrompt}
                      onChange={(e) => {
                        setCustomPrompt(e.target.value);
                        setHasUserEditedPrompt(true);
                        // Clear validation error when user starts typing
                        if (validationError) setValidationError(null);
                      }}
                      rows={12}
                      className="resize-none font-mono text-xs leading-relaxed"
                      placeholder="Enter your custom prompt..."
                    />
                    <div className="text-xs text-muted-foreground">
                      {customPrompt.length} characters
                    </div>
                  </div>
                </AccordionContent>
              </AccordionItem>
            </Accordion>
          )}
        </DialogAutoScrollBody>
        <DialogFooter className="gap-2 sm:space-x-0">
          <DialogClose asChild>
            <Button variant="outline" size="lg" disabled={isPending}>
              Cancel
            </Button>
          </DialogClose>
          <Button
            onClick={handleSubmit}
            disabled={
              !selectedModel || !initialDatasetId || isAnalyzing || isPending
            }
            className={isPending ? "relative overflow-hidden" : ""}
            size="lg"
          >
            {isPending && <Spinner size="small" className="mr-2" />}
            {isPending ? (
              <div className="flex items-center gap-2">
                <div className="flex flex-col items-start gap-1">
                  <span className="text-sm font-medium">{progressMessage}</span>
                  <div className="flex items-center gap-2">
                    <div className="h-1.5 w-32 overflow-hidden rounded-full bg-primary/20">
                      <div
                        className="h-full bg-primary transition-all duration-300 ease-out"
                        style={{ width: `${generationProgress}%` }}
                      />
                    </div>
                    <span className="text-xs text-muted-foreground">
                      {Math.round(generationProgress)}%
                    </span>
                  </div>
                </div>
              </div>
            ) : (
              "Generate Samples"
            )}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default DatasetExpansionDialog;
