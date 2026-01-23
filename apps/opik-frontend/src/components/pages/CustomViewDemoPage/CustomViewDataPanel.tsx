import React, { useState, useEffect } from "react";
import { AlertCircle, Loader2, Sparkles } from "lucide-react";
import { Button } from "@/components/ui/button";
import { CustomViewSchema, WidgetSize, ContextData } from "@/types/custom-view";
import { resolveTracePath } from "@/lib/tracePathResolver";
import WidgetRenderer from "./widgets/WidgetRenderer";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

interface CustomViewDataPanelProps {
  data: ContextData | null | undefined;
  viewSchema: CustomViewSchema | null;
  isDataLoading: boolean;
  isDataError: boolean;
  isAIGenerating: boolean;
  isAPIError: boolean;
  apiError: string | null;
}

const CustomViewDataPanel: React.FC<CustomViewDataPanelProps> = ({
  data,
  viewSchema,
  isDataLoading,
  isDataError,
  isAIGenerating,
  isAPIError,
  apiError,
}) => {
  // Determine the current state
  const getState = () => {
    if (!data && !isDataLoading) return "idle";
    if (isDataError) return "data-error";
    if (isDataLoading) return "loading";
    if (isAPIError && !isAIGenerating) return "api-error";
    if (isAIGenerating) return "generating";
    if (data && !viewSchema) return "awaiting-generation";
    if (data && viewSchema) return "ready";
    return "idle";
  };

  const state = getState();

  return (
    <div className="flex h-full flex-col overflow-hidden">
      {/* Header - Sticky */}
      <div className="border-b p-4">
        <h2 className="comet-title-m">Data Visualization</h2>
      </div>

      {/* Content - Scrollable */}
      <div className="relative flex-1 overflow-y-auto p-6">
        {state === "loading" && <LoadingOverlay />}
        {state === "generating" && <GeneratingOverlay />}
        {state === "idle" && <IdleState />}
        {state === "awaiting-generation" && <AwaitingGenerationState />}
        {state === "ready" && (
          <ReadyState data={data!} viewSchema={viewSchema!} />
        )}
        {state === "data-error" && <DataErrorState />}
        {state === "api-error" && <APIErrorState error={apiError} />}
      </div>
    </div>
  );
};

const IdleState = () => (
  <div className="flex h-full items-center justify-center text-center">
    <div>
      <div className="comet-title-m mb-2 text-muted-slate">
        No Context Selected
      </div>
      <div className="comet-body-s text-muted-slate">
        Select a trace or thread and ask AI to generate a custom view
      </div>
    </div>
  </div>
);

const AwaitingGenerationState = () => (
  <div className="flex h-full items-center justify-center text-center">
    <div className="max-w-md">
      <Sparkles className="mx-auto mb-4 size-12 text-primary" />
      <div className="comet-title-m mb-2">Context Selected</div>
      <div className="comet-body-s text-muted-slate">
        Ask AI to analyze the data and generate a custom visualization. Try
        prompts like:
      </div>
      <div className="mt-4 space-y-2 text-left">
        <div className="rounded-md bg-muted/30 p-3 text-sm">
          &ldquo;Show me the most important fields in this data&rdquo;
        </div>
        <div className="rounded-md bg-muted/30 p-3 text-sm">
          &ldquo;Create a view focusing on the conversation messages&rdquo;
        </div>
        <div className="rounded-md bg-muted/30 p-3 text-sm">
          &ldquo;Display the input, output, and usage metrics&rdquo;
        </div>
      </div>
    </div>
  </div>
);

const LoadingOverlay = () => {
  return (
    <div className="absolute inset-0 z-10 flex items-center justify-center bg-background/80 backdrop-blur-sm">
      <div className="flex flex-col items-center gap-3">
        <Loader2 className="size-8 animate-spin text-primary" />
        <div className="flex flex-col items-center gap-1">
          <span className="comet-body-s-accented">Loading data...</span>
        </div>
      </div>
    </div>
  );
};

const GeneratingOverlay = () => {
  const magicMessages = [
    "✨ AI magic in progress",
    "🔮 Analyzing data",
    "🎨 Creating custom view",
    "⚡ Preparing visualization",
  ];
  const [messageIndex, setMessageIndex] = useState(0);

  useEffect(() => {
    const interval = setInterval(() => {
      setMessageIndex((prev: number) => (prev + 1) % magicMessages.length);
    }, 2000);
    return () => clearInterval(interval);
  }, [magicMessages.length]);

  return (
    <div className="absolute inset-0 z-10 flex items-center justify-center bg-background/80 backdrop-blur-sm">
      <div className="flex flex-col items-center gap-3">
        <Loader2 className="size-8 animate-spin text-primary" />
        <div className="flex flex-col items-center gap-1">
          <span className="comet-body-s-accented">
            Generating visualization...
          </span>
          <span className="comet-body-xs animate-pulse text-muted-slate">
            {magicMessages[messageIndex]}
          </span>
        </div>
      </div>
    </div>
  );
};

const ReadyState = ({
  data,
  viewSchema,
}: {
  data: ContextData;
  viewSchema: CustomViewSchema;
}) => {
  if (!viewSchema.widgets || viewSchema.widgets.length === 0) {
    return (
      <div className="flex h-full items-center justify-center text-center">
        <div>
          <div className="comet-title-m mb-2 text-muted-slate">
            No Widgets Generated
          </div>
          <div className="comet-body-s text-muted-slate">
            The AI didn&apos;t generate any widgets. Try a different prompt.
          </div>
        </div>
      </div>
    );
  }

  // Helper function to get column span classes based on widget size
  const getColumnSpanClass = (size: WidgetSize): string => {
    switch (size) {
      case WidgetSize.SMALL:
        return "col-span-6 md:col-span-3 xl:col-span-2";
      case WidgetSize.MEDIUM:
        return "col-span-6 md:col-span-6 xl:col-span-3";
      case WidgetSize.LARGE:
        return "col-span-6 xl:col-span-4";
      case WidgetSize.FULL:
        return "col-span-6";
    }
  };

  return (
    <div className="grid grid-cols-6 gap-6">
      {viewSchema.widgets.map((widget, index) => {
        const value = resolveTracePath(data, widget.path);
        const columnSpanClass = getColumnSpanClass(widget.size);

        return (
          <TooltipWrapper key={index} content={`Path: ${widget.path}`}>
            <div className={columnSpanClass}>
              <WidgetRenderer
                type={widget.uiWidget}
                value={value}
                label={widget.label}
                path={widget.path}
              />
            </div>
          </TooltipWrapper>
        );
      })}
    </div>
  );
};

const DataErrorState = () => (
  <div className="flex h-full items-center justify-center text-center">
    <div className="max-w-md">
      <AlertCircle className="mx-auto mb-4 size-12 text-destructive" />
      <div className="comet-title-m mb-2 text-destructive">
        Error Loading Data
      </div>
      <div className="comet-body-s mb-4 text-muted-slate">
        An error occurred while loading the data. The item may not exist
        or you may not have permission to view it.
      </div>
      <Button variant="outline" onClick={() => window.location.reload()}>
        Retry
      </Button>
    </div>
  </div>
);

const APIErrorState = ({ error }: { error: string | null }) => (
  <div className="flex h-full items-center justify-center text-center">
    <div className="max-w-md">
      <AlertCircle className="mx-auto mb-4 size-12 text-destructive" />
      <div className="comet-title-m mb-2 text-destructive">
        AI Generation Failed
      </div>
      <div className="comet-body-s mb-4 text-muted-slate">
        The AI couldn&apos;t generate a custom view for this data.
        {error && (
          <div className="mt-2 rounded-md bg-destructive/10 p-3 text-left text-sm text-destructive">
            {error}
          </div>
        )}
      </div>
      <div className="comet-body-s mb-4 text-left text-muted-slate">
        <div className="comet-body-s-accented mb-2">Try:</div>
        <ul className="list-inside list-disc space-y-1">
          <li>Check your network connection</li>
          <li>Simplify your prompt</li>
          <li>Select a different model</li>
          <li>Use the retry button in the chat panel</li>
        </ul>
      </div>
    </div>
  </div>
);

export default CustomViewDataPanel;
