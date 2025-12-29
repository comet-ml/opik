import React, {
  useState,
  useCallback,
  useEffect,
  useMemo,
  useRef,
} from "react";
import { Wand2, Loader2, Play, ChevronRight, Sparkles } from "lucide-react";
import CodeMirror from "@uiw/react-codemirror";
import { EditorView } from "@codemirror/view";

import {
  Dialog,
  DialogAutoScrollBody,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Alert, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import { Description } from "@/components/ui/description";
import { Separator } from "@/components/ui/separator";
import ExplainerDescription from "@/components/shared/ExplainerDescription/ExplainerDescription";
import PromptModelSelect from "@/components/pages-shared/llm/PromptModelSelect/PromptModelSelect";
import usePromptImprovement from "@/hooks/usePromptImprovement";
import useProgressSimulation from "@/hooks/useProgressSimulation";
import useModelSelection from "@/hooks/useModelSelection";
import {
  COMPOSED_PROVIDER_TYPE,
  LLMPromptConfigsType,
} from "@/types/providers";
import { PROVIDERS } from "@/constants/providers";
import { MessageContent } from "@/types/llm";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import ExplainerCallout from "@/components/shared/ExplainerCallout/ExplainerCallout";
import {
  codeMirrorPromptTheme,
  mustachePlugin,
} from "@/constants/codeMirrorPlugins";
import { cn } from "@/lib/utils";
import { parseComposedProviderType } from "@/lib/provider";
import { parseLLMMessageContent } from "@/lib/llm";

const PROMPT_IMPROVEMENT_PROGRESS_MESSAGES = [
  "Analyzing your instructions...",
  "Defining the prompt structure...",
  "Scoping the role and perspective...",
  "Applying best practices...",
  "Synthesizing the full prompt...",
  "Polishing the output...",
];

const PROMPT_IMPROVEMENT_LAST_PICKED_MODEL = "opik-prompt-improvement-model";

interface PromptImprovementDialogProps {
  open: boolean;
  setOpen: (open: boolean) => void;
  id: string;
  originalPrompt?: MessageContent;
  model: string;
  provider: COMPOSED_PROVIDER_TYPE;
  configs: LLMPromptConfigsType;
  workspaceName: string;
  onAccept: (messageId: string, improvedPrompt: MessageContent) => void;
}

const PromptImprovementDialog: React.FC<PromptImprovementDialogProps> = ({
  open,
  setOpen,
  id,
  originalPrompt = "",
  model: defaultModel,
  provider: defaultProvider,
  configs: defaultConfigs,
  workspaceName,
  onAccept,
}) => {
  const [userInstructions, setUserInstructions] = useState("");
  const [generatedPrompt, setGeneratedPrompt] = useState("");
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [isEditorFocused, setIsEditorFocused] = useState(false);
  const editorViewRef = useRef<EditorView | null>(null);

  // Model selection with persistence using the reusable hook
  const { model, provider, configs, modelSelectProps } = useModelSelection({
    persistenceKey: PROMPT_IMPROVEMENT_LAST_PICKED_MODEL,
    defaultModel,
    defaultProvider,
    defaultConfigs,
  });

  const { improvePrompt, generatePrompt } = usePromptImprovement({
    workspaceName,
  });

  const { message: progressMessage } = useProgressSimulation({
    messages: PROMPT_IMPROVEMENT_PROGRESS_MESSAGES,
    isPending: isLoading && !generatedPrompt,
    intervalMs: 2000,
  });

  const {
    text: originalPromptText,
    images: originalImages,
    videos: originalVideos,
  } = useMemo(() => parseLLMMessageContent(originalPrompt), [originalPrompt]);

  const isGenerateMode = !originalPromptText?.trim();
  const title = isGenerateMode ? "Generate prompt" : "Improve prompt";
  const hasInstructions = Boolean(userInstructions.trim());

  useEffect(() => {
    if (open) {
      setUserInstructions("");
      setGeneratedPrompt("");
      setError(null);
      setIsLoading(false);
      setIsEditorFocused(false);
    }
  }, [open, originalImages, originalVideos]);

  // Smart auto-scroll: only auto-scroll when user is near the bottom
  // This allows users to scroll up to review content without being forced down
  useEffect(() => {
    if (!isLoading || !generatedPrompt || !editorViewRef.current) return;

    requestAnimationFrame(() => {
      const editor = editorViewRef.current;
      if (!editor) return;

      const scrollDOM = editor.scrollDOM;
      const { scrollTop, scrollHeight, clientHeight } = scrollDOM;

      // Check if user is within 50px of the bottom
      const isNearBottom = scrollHeight - scrollTop - clientHeight < 50;

      // Only auto-scroll if user is near the bottom or just started
      if (isNearBottom || scrollTop === 0) {
        scrollDOM.scrollTop = scrollHeight;
      }
    });
  }, [generatedPrompt, isLoading]);

  const handleGenerateOrImprove = useCallback(async () => {
    if (isGenerateMode && !hasInstructions) return;

    setIsLoading(true);
    setError(null);
    setGeneratedPrompt("");

    try {
      const controller = new AbortController();

      let result;
      if (isGenerateMode) {
        result = await generatePrompt(
          userInstructions,
          model,
          configs,
          (chunk) => {
            setGeneratedPrompt(chunk);
          },
          controller.signal,
        );
      } else {
        result = await improvePrompt(
          originalPromptText,
          userInstructions,
          model,
          configs,
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
    hasInstructions,
    userInstructions,
    isGenerateMode,
    generatePrompt,
    improvePrompt,
    originalPromptText,
    model,
    configs,
  ]);

  const hasPrompt = generatedPrompt.trim() && !isLoading && !error;

  const handleSuccessClick = useCallback(() => {
    if (hasPrompt) {
      // Combine generated text with original images and videos into MessageContent
      let finalPrompt: MessageContent;
      if (originalImages.length === 0 && originalVideos.length === 0) {
        finalPrompt = generatedPrompt;
      } else {
        const parts: MessageContent = [];
        if (generatedPrompt.trim()) {
          parts.push({ type: "text", text: generatedPrompt });
        }
        originalImages.forEach((url) => {
          parts.push({ type: "image_url", image_url: { url } });
        });
        originalVideos.forEach((url) => {
          parts.push({ type: "video_url", video_url: { url } });
        });
        finalPrompt = parts;
      }
      onAccept(id, finalPrompt);
      setOpen(false);
    }
  }, [
    hasPrompt,
    generatedPrompt,
    originalImages,
    originalVideos,
    onAccept,
    id,
    setOpen,
  ]);

  const instructionsPlaceholder = isGenerateMode
    ? "What do you want your AI to do?"
    : "What do you want to improve (e.g., tone, length)";

  const modelDisplayName = useMemo(() => {
    if (!model) return "not configured";
    const providerLabel = provider
      ? PROVIDERS[parseComposedProviderType(provider)]?.label
      : "";
    return providerLabel ? `${providerLabel} ${model}` : model;
  }, [model, provider]);

  const renderInstructionsSection = (height: string) => (
    <div className="flex flex-1 flex-col gap-2">
      <Textarea
        className={`comet-code ${height} resize-none`}
        placeholder={instructionsPlaceholder}
        value={userInstructions}
        onChange={(e) => setUserInstructions(e.target.value)}
        autoFocus
      />
    </div>
  );

  const renderArrow = () => (
    <div className="pointer-events-none absolute left-1/2 top-1/2 z-10 flex size-8 -translate-x-1/2 -translate-y-1/2 items-center justify-center rounded-full bg-primary-100">
      <ChevronRight className="size-3.5 text-primary" />
    </div>
  );

  const renderRightSectionTitle = (label: string) => (
    <div className="flex flex-col gap-2">
      <div className="comet-body-accented">{label}</div>
      <Description>
        This is your generated prompt, created with the selected model (
        {modelDisplayName}) and parameters. It&apos;s editable.
      </Description>
    </div>
  );

  const renderGeneratedPromptSection = () => {
    const isEmpty = !generatedPrompt && !isLoading;
    const isLoadingEmpty = isLoading && !generatedPrompt;
    const isStreaming = isLoading && generatedPrompt;
    const isEditable = !isLoading && Boolean(generatedPrompt) && !error;

    return (
      <div className="flex flex-col gap-2">
        <div className="relative h-[320px]">
          {isEmpty && (
            <div className="absolute inset-0 flex flex-col items-center justify-center gap-2 rounded-md border border-dashed p-12">
              <Sparkles className="size-4 text-light-slate" />
              <div className="comet-body-s text-center text-muted-slate">
                The generated prompt will be shown here, ready for you to review
                and edit
              </div>
            </div>
          )}

          {isLoadingEmpty && (
            <div className="absolute inset-0 flex flex-col items-center justify-center gap-3 rounded-md border">
              <Loader2 className="size-6 animate-spin text-primary" />
              {progressMessage && (
                <div className="comet-body-s text-muted-slate">
                  {progressMessage}
                </div>
              )}
            </div>
          )}

          {(isStreaming || isEditable) && (
            <div
              className={cn(
                "rounded-md border px-3 py-2 transition-colors min-h-[320px]",
                isEditorFocused && isEditable && "border-primary",
              )}
            >
              <CodeMirror
                onCreateEditor={(view) => {
                  editorViewRef.current = view;
                }}
                onFocus={() => setIsEditorFocused(true)}
                onBlur={() => setIsEditorFocused(false)}
                theme={codeMirrorPromptTheme}
                value={generatedPrompt}
                onChange={(value) => setGeneratedPrompt(value)}
                placeholder="The generated prompt will be shown here, ready for you to review and edit"
                editable={isEditable}
                basicSetup={{
                  foldGutter: false,
                  allowMultipleSelections: false,
                  lineNumbers: false,
                  highlightActiveLine: false,
                }}
                extensions={[EditorView.lineWrapping, mustachePlugin]}
                maxHeight="302px"
              />
            </div>
          )}
        </div>
      </div>
    );
  };

  const renderGenerateButtons = () => (
    <>
      <Button
        variant={hasPrompt ? "outline" : "default"}
        onClick={handleGenerateOrImprove}
        disabled={(isGenerateMode && !hasInstructions) || isLoading}
        className="w-full"
      >
        <Sparkles className="mr-2 size-4 shrink-0" />
        {hasPrompt
          ? isGenerateMode
            ? "Re-generate prompt"
            : "Re-run improvement"
          : isGenerateMode
            ? "Generate prompt"
            : "Improve prompt"}
      </Button>
      <Button
        variant={!hasPrompt ? "outline" : "default"}
        onClick={handleSuccessClick}
        disabled={!hasPrompt}
      >
        <Play className="mr-2 size-4 shrink-0" />
        Use this prompt
      </Button>
    </>
  );

  const renderImproveContent = () => {
    return (
      <div className="relative grid grid-cols-2 gap-x-14 gap-y-3">
        {renderArrow()}
        <div className="flex flex-col gap-2">
          <div className="comet-body-accented">Your initial prompt</div>
          <Description>
            This is your initial prompt. Optional instructions can guide
            improvements, or we’ll optimize it automatically.
          </Description>
        </div>
        {renderRightSectionTitle("Improved prompt")}
        <div className="flex flex-col gap-2">
          <div className="comet-code h-[120px] overflow-y-auto whitespace-pre-wrap break-words rounded-md border bg-primary-foreground p-3 text-light-slate">
            {originalPromptText}
          </div>
          <div className="mt-1 flex flex-col">
            <div className="comet-title-xs">Instructions (optional)</div>
            <Description>
              Write your instructions in plain language. We’ll turn them into a
              best-practice prompt that you can review and iterate on.
            </Description>
          </div>
          {renderInstructionsSection("min-h-[120px]")}
        </div>
        {renderGeneratedPromptSection()}
        {renderGenerateButtons()}
      </div>
    );
  };

  const renderGenerateContent = () => {
    return (
      <div className="relative grid grid-cols-2 gap-x-14 gap-y-3 pb-4">
        {renderArrow()}
        <div className="flex flex-col gap-2">
          <div className="comet-body-accented">Instructions</div>
          <Description>
            Write your instructions in plain language. We’ll turn them into a
            best-practice prompt that you can review and iterate on.
          </Description>
        </div>
        {renderRightSectionTitle("Generated prompt")}
        {renderInstructionsSection("h-[320px]")}
        {renderGeneratedPromptSection()}
        {renderGenerateButtons()}
      </div>
    );
  };

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent
        className="max-w-[960px]"
        onClick={(e) => e.stopPropagation()}
      >
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <Wand2 className="size-5" />
            {title}
          </DialogTitle>
          <ExplainerDescription
            {...EXPLAINERS_MAP[
              isGenerateMode
                ? EXPLAINER_ID.prompt_generation_learn_more
                : EXPLAINER_ID.prompt_improvement_learn_more
            ]}
          />
        </DialogHeader>
        <DialogAutoScrollBody>
          <div className="mb-4 flex items-center gap-3">
            <div className="comet-body-accented shrink-0">Model</div>
            <div className="w-64">
              <PromptModelSelect
                workspaceName={workspaceName}
                {...modelSelectProps}
                disabled={isLoading}
              />
            </div>
          </div>
          {error && (
            <Alert variant="destructive" className="mb-4">
              <AlertTitle>{error}</AlertTitle>
            </Alert>
          )}
          {isGenerateMode ? renderGenerateContent() : renderImproveContent()}
          {!isGenerateMode && (
            <>
              <Separator orientation="horizontal" className="my-6" />
              <ExplainerCallout
                {...EXPLAINERS_MAP[EXPLAINER_ID.prompt_improvement_optimizer]}
                isDismissable={false}
              />
            </>
          )}
        </DialogAutoScrollBody>
      </DialogContent>
    </Dialog>
  );
};

export default PromptImprovementDialog;
