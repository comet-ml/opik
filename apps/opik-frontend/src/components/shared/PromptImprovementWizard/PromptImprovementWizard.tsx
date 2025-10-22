import React, {
  useState,
  useCallback,
  useEffect,
  useMemo,
  useRef,
} from "react";
import { Wand2, Loader2 } from "lucide-react";

import {
  Dialog,
  DialogAutoScrollBody,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Label } from "@/components/ui/label";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";
import { Description } from "@/components/ui/description";
import { FormErrorSkeleton } from "@/components/ui/form";
import { Tag } from "@/components/ui/tag";
import TextDiff from "@/components/shared/CodeDiff/TextDiff";
import usePromptImprovement from "@/hooks/usePromptImprovement";
import useAdjustedLLMConfigs from "@/hooks/useAdjustedLLMConfigs";
import { LLMPromptConfigsType, PROVIDER_TYPE } from "@/types/providers";
import { PROVIDERS } from "@/constants/providers";
import { getPromptMustacheTags } from "@/lib/prompt";
import { parseContentWithImages, combineContentWithImages } from "@/lib/llm";
import { DEFAULT_IMPROVEMENT_INSTRUCTION } from "@/constants/promptImprovement";

enum WIZARD_STEP {
  instructions = "instructions",
  review = "review",
}

enum PROMPT_PREVIEW_MODE {
  write = "write",
  diff = "diff",
}

interface PromptImprovementWizardProps {
  open: boolean;
  setOpen: (open: boolean) => void;
  id: string;
  originalPrompt?: string;
  model: string;
  provider: PROVIDER_TYPE | "";
  configs: LLMPromptConfigsType;
  workspaceName: string;
  onAccept: (messageId: string, improvedPrompt: string) => void;
}

const PromptImprovementWizard: React.FC<PromptImprovementWizardProps> = ({
  open,
  setOpen,
  id,
  originalPrompt = "",
  model,
  provider,
  configs,
  workspaceName,
  onAccept,
}) => {
  const [currentStep, setCurrentStep] = useState<WIZARD_STEP>(
    WIZARD_STEP.instructions,
  );
  const [userInstructions, setUserInstructions] = useState("");
  const [generatedPrompt, setGeneratedPrompt] = useState("");
  const [images, setImages] = useState<string[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [previewMode, setPreviewMode] = useState<PROMPT_PREVIEW_MODE>(
    PROMPT_PREVIEW_MODE.write,
  );
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  const { improvePrompt, generatePrompt } = usePromptImprovement({
    workspaceName,
  });

  // Validate and adjust LLM configs to ensure sufficient token limits
  const { adjustedConfigs, needsAdjustment, minTokens } =
    useAdjustedLLMConfigs(configs);

  const { text: originalPromptText, images: originalImages } = useMemo(
    () => parseContentWithImages(originalPrompt),
    [originalPrompt],
  );

  const hasOriginalPrompt = Boolean(originalPromptText?.trim());
  const isGenerateMode = !hasOriginalPrompt;

  useEffect(() => {
    if (open) {
      setCurrentStep(WIZARD_STEP.instructions);
      setUserInstructions("");
      setGeneratedPrompt("");
      setImages(originalImages);
      setError(null);
      setPreviewMode(PROMPT_PREVIEW_MODE.write);
      setIsLoading(false);
    }
  }, [open, originalImages]);

  // Smart auto-scroll: only auto-scroll when user is near the bottom
  // This allows users to scroll up to review content without being forced down
  useEffect(() => {
    if (!isLoading || !textareaRef.current) return;

    requestAnimationFrame(() => {
      const textarea = textareaRef.current;
      if (!textarea) return;

      const { scrollTop, scrollHeight, clientHeight } = textarea;
      // Check if user is within 50px of the bottom
      const isNearBottom = scrollHeight - scrollTop - clientHeight < 50;

      // Only auto-scroll if user is near the bottom or just started
      if (isNearBottom || scrollTop === 0) {
        textarea.scrollTop = scrollHeight;
      }
    });
  }, [generatedPrompt, isLoading]);

  const handleGenerateOrImprove = useCallback(async () => {
    if (isGenerateMode && !userInstructions.trim()) return;

    setIsLoading(true);
    setError(null);
    setGeneratedPrompt("");
    setCurrentStep(WIZARD_STEP.review);

    try {
      const controller = new AbortController();

      const instructions =
        !isGenerateMode && !userInstructions.trim()
          ? DEFAULT_IMPROVEMENT_INSTRUCTION
          : userInstructions;

      let result;
      if (isGenerateMode) {
        result = await generatePrompt(
          instructions,
          model,
          adjustedConfigs,
          (chunk) => {
            setGeneratedPrompt(chunk);
          },
          controller.signal,
        );
      } else {
        result = await improvePrompt(
          originalPromptText,
          instructions,
          model,
          adjustedConfigs,
          (chunk) => {
            setGeneratedPrompt(chunk);
          },
          controller.signal,
        );
      }

      if (
        result?.opikError ||
        result?.providerError ||
        result?.pythonProxyError
      ) {
        const errorMsg =
          result.opikError || result.providerError || result.pythonProxyError;
        setError(errorMsg || "An error occurred during generation");
      } else if (
        result?.choices?.[0]?.finish_reason === "length" ||
        result?.choices?.some((choice) => choice.finish_reason === "length")
      ) {
        setError(
          "The generated prompt was cut off due to token limits. Please try increasing the max_tokens setting in the model configuration or use a shorter instruction.",
        );
      } else if (!result?.result || !result.result.trim()) {
        setError(
          "The model did not return any content. Please try again or adjust your instructions.",
        );
      }
    } catch (err) {
      const errorMessage =
        err instanceof Error
          ? err.message
          : isGenerateMode
            ? "Failed to generate prompt"
            : "Failed to improve prompt";
      setError(errorMessage);
    } finally {
      setIsLoading(false);
    }
  }, [
    userInstructions,
    isGenerateMode,
    generatePrompt,
    improvePrompt,
    originalPromptText,
    model,
    adjustedConfigs,
  ]);

  const handleBack = useCallback(() => {
    setCurrentStep(WIZARD_STEP.instructions);
    setError(null);
    setGeneratedPrompt("");
  }, []);

  const handleContinue = useCallback(() => {
    if (generatedPrompt.trim()) {
      const finalPrompt = combineContentWithImages(generatedPrompt, images);
      onAccept(id, finalPrompt);
      setOpen(false);
    }
  }, [generatedPrompt, images, onAccept, setOpen, id]);

  const handleCancel = useCallback(() => {
    setOpen(false);
  }, [setOpen]);

  const variables = useMemo(() => {
    if (!generatedPrompt) return [];
    try {
      return getPromptMustacheTags(generatedPrompt);
    } catch {
      return [];
    }
  }, [generatedPrompt]);

  const hasChanges =
    generatedPrompt.trim() && generatedPrompt !== originalPromptText;
  const canContinue = generatedPrompt.trim() && !isLoading && !error;

  const title = isGenerateMode
    ? "Generate prompt with OpikAI"
    : "Improve prompt with OpikAI";

  const instructionsPlaceholder = isGenerateMode
    ? "Describe your task..."
    : "What would you like to improve?";

  const modelDisplayName = useMemo(() => {
    if (!model) return "not configured";
    const providerLabel = provider ? PROVIDERS[provider]?.label : "";
    return providerLabel ? `${providerLabel} ${model}` : model;
  }, [model, provider]);

  const actionButtonText = isGenerateMode
    ? "Generate prompt"
    : "Improve prompt";

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent
        className="max-w-lg sm:max-w-[720px]"
        onClick={(e) => e.stopPropagation()}
      >
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <Wand2 className="size-5" />
            {title}
          </DialogTitle>
        </DialogHeader>
        <DialogAutoScrollBody>
          {currentStep === WIZARD_STEP.instructions ? (
            <div className="flex flex-col gap-4 pb-4">
              {hasOriginalPrompt && (
                <div className="flex flex-col gap-2">
                  <Label>Original prompt</Label>
                  <div className="comet-code max-h-[300px] overflow-y-auto whitespace-pre-wrap rounded-md border bg-muted p-3 text-muted-foreground">
                    {originalPromptText}
                  </div>
                </div>
              )}

              <div className="flex flex-col gap-2">
                <Label>Instructions</Label>
                <Textarea
                  className="comet-code min-h-32"
                  placeholder={instructionsPlaceholder}
                  value={userInstructions}
                  onChange={(e) => setUserInstructions(e.target.value)}
                  autoFocus
                />
              </div>

              <div>
                <Description>
                  {isGenerateMode
                    ? "The prompt will be generated"
                    : "The prompt will be improved"}{" "}
                  using the selected model (
                  <span className="font-semibold">{modelDisplayName}</span>) and
                  parameters defined for this prompt.
                  {needsAdjustment &&
                    ` The max output tokens parameter will be automatically increased to ${minTokens} tokens to ensure comprehensive prompt generation.`}
                </Description>
              </div>

              {error && <FormErrorSkeleton>{error}</FormErrorSkeleton>}
            </div>
          ) : (
            <div className="flex flex-col gap-4 pb-4">
              {hasOriginalPrompt && !isLoading && (
                <div className="flex items-center justify-end">
                  <ToggleGroup
                    type="single"
                    value={previewMode}
                    onValueChange={(value) =>
                      value && setPreviewMode(value as PROMPT_PREVIEW_MODE)
                    }
                    size="sm"
                  >
                    <ToggleGroupItem
                      value={PROMPT_PREVIEW_MODE.write}
                      aria-label="Write"
                    >
                      Write
                    </ToggleGroupItem>
                    <ToggleGroupItem
                      value={PROMPT_PREVIEW_MODE.diff}
                      aria-label="Preview changes"
                      disabled={!hasChanges}
                    >
                      Preview changes
                    </ToggleGroupItem>
                  </ToggleGroup>
                </div>
              )}

              {error && <FormErrorSkeleton>{error}</FormErrorSkeleton>}

              {previewMode === PROMPT_PREVIEW_MODE.write ? (
                <div className="relative">
                  <Textarea
                    ref={textareaRef}
                    className="comet-code h-[400px] resize-none overflow-y-auto"
                    value={generatedPrompt}
                    onChange={(e) => setGeneratedPrompt(e.target.value)}
                    disabled={isLoading}
                  />
                  {isLoading && !generatedPrompt && (
                    <div className="absolute left-3 top-3 flex items-center gap-2">
                      <Loader2 className="size-4 animate-spin" />
                      <span className="comet-body-s text-muted-foreground">
                        {isGenerateMode
                          ? "Generating prompt..."
                          : "Improving prompt..."}
                      </span>
                    </div>
                  )}
                </div>
              ) : (
                <div className="comet-code h-[400px] overflow-y-auto whitespace-pre-line break-words rounded-md border px-2.5 py-1.5">
                  <TextDiff
                    content1={originalPromptText}
                    content2={generatedPrompt}
                  />
                </div>
              )}

              {variables.length > 0 && !isLoading && (
                <div className="flex flex-col gap-2">
                  <Label>Variables</Label>
                  <Description>
                    This prompt includes variables that can be populated with
                    data from your dataset. Variables use the{" "}
                    <code className="comet-code rounded bg-background px-1 py-0.5">
                      {"{{VARIABLE_NAME}}"}
                    </code>{" "}
                    format and must match column names in your dataset:
                  </Description>
                  <div className="flex flex-wrap gap-2">
                    {variables.map((variable, index) => (
                      <Tag key={index} variant="green" size="sm">
                        {`{{${variable}}}`}
                      </Tag>
                    ))}
                  </div>
                </div>
              )}
            </div>
          )}
        </DialogAutoScrollBody>
        <DialogFooter>
          {currentStep === WIZARD_STEP.instructions ? (
            <>
              <Button variant="outline" onClick={handleCancel}>
                Cancel
              </Button>
              <Button
                onClick={handleGenerateOrImprove}
                disabled={isGenerateMode && !userInstructions.trim()}
              >
                {actionButtonText}
              </Button>
            </>
          ) : (
            <>
              <Button variant="ghost" onClick={handleCancel}>
                Cancel
              </Button>
              <div className="flex-1" />
              <Button variant="outline" onClick={handleBack}>
                Keep refining
              </Button>
              <Button onClick={handleContinue} disabled={!canContinue}>
                Use this prompt
              </Button>
            </>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default PromptImprovementWizard;
