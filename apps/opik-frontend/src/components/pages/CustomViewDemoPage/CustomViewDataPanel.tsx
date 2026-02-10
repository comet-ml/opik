import React, { useState, useEffect } from "react";
import { AlertCircle, Loader2, Sparkles } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Renderer, useDataView } from "@/lib/data-view";
import { customViewRegistry } from "@/components/shared/data-view-widgets";

interface CustomViewDataPanelProps {
  isDataLoading: boolean;
  isDataError: boolean;
  isAIGenerating: boolean;
  isStreaming: boolean;
  patchCount: number;
  isAPIError: boolean;
  apiError: string | null;
}

const CustomViewDataPanel: React.FC<CustomViewDataPanelProps> = ({
  isDataLoading,
  isDataError,
  isAIGenerating,
  isStreaming,
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  patchCount,
  isAPIError,
  apiError,
}) => {
  // Get tree from context (single DataViewProvider in parent)
  const { tree, source } = useDataView();

  // Determine the current state based on context
  const getState = () => {
    const hasSource = source && Object.keys(source).length > 0;
    const hasTree = tree?.root && Object.keys(tree.nodes || {}).length > 0;
    const isGeneratingOrStreaming = isAIGenerating || isStreaming;

    if (!hasSource && !isDataLoading) return "idle";
    if (isDataError) return "data-error";
    if (isDataLoading) return "loading";
    if (isAPIError && !isGeneratingOrStreaming) return "api-error";

    // Show tree content with streaming indicator when we have tree content during generation
    if (isGeneratingOrStreaming && hasTree) return "streaming";

    // Show generating overlay only when no tree content yet
    if (isGeneratingOrStreaming && !hasTree) return "generating";

    if (hasSource && !hasTree) return "awaiting-generation";
    if (hasSource && hasTree) return "ready";
    return "idle";
  };

  const state = getState();

  return (
    <div className="flex h-full flex-col overflow-hidden">
      {/* Content - Scrollable */}
      <div className="relative flex-1 overflow-y-auto p-6">
        {state === "loading" && <LoadingOverlay />}
        {state === "generating" && <GeneratingOverlay />}
        {state === "streaming" && <StreamingState />}
        {state === "idle" && <IdleState />}
        {state === "awaiting-generation" && <AwaitingGenerationState />}
        {state === "ready" && <ReadyState />}
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

const STREAMING_MESSAGES = [
  "Crafting your view...",
  "Organizing data...",
  "Building layout...",
  "Adding details...",
  "Almost there...",
];

const StreamingState = () => {
  const [messageIndex, setMessageIndex] = useState(0);

  useEffect(() => {
    const interval = setInterval(() => {
      setMessageIndex((prev) => (prev + 1) % STREAMING_MESSAGES.length);
    }, 2000);
    return () => clearInterval(interval);
  }, []);

  return (
    <div className="relative">
      {/* Streaming indicator badge */}
      <div className="absolute right-0 top-0 z-10 flex items-center gap-2 rounded-bl-lg bg-gradient-to-r from-primary/5 to-primary/10 px-4 py-2.5 backdrop-blur-sm">
        <Sparkles className="size-4 animate-pulse text-primary" />
        <span className="comet-body-s-accented text-primary">
          {STREAMING_MESSAGES[messageIndex]}
        </span>
      </div>

      {/* Tree content - renders progressively */}
      <div className="space-y-4 pt-12">
        <Renderer registry={customViewRegistry} />
      </div>
    </div>
  );
};

const ReadyState = () => {
  // Use context from parent DataViewProvider - no nested provider needed
  const { tree } = useDataView();

  // Check if the tree has a valid root and nodes
  if (!tree?.root || !tree.nodes || Object.keys(tree.nodes).length === 0) {
    return (
      <div className="flex h-full items-center justify-center text-center">
        <div>
          <div className="comet-title-m mb-2 text-muted-slate">
            No Content Generated
          </div>
          <div className="comet-body-s text-muted-slate">
            The AI didn&apos;t generate any content. Try a different prompt.
          </div>
        </div>
      </div>
    );
  }

  // Renderer reads tree and source from context automatically
  return (
    <div className="rounded-md border bg-background p-4">
      <Renderer registry={customViewRegistry} />
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
        An error occurred while loading the data. The item may not exist or you
        may not have permission to view it.
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
