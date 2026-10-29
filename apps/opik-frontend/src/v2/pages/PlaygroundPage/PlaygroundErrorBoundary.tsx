import React, { useCallback } from "react";
import * as Sentry from "@sentry/react";
import copy from "clipboard-copy";
import { Copy, ExternalLink, RotateCcw } from "lucide-react";

import somethingWentWrongImage from "/images/something-went-wrong.png";
import { Button } from "@/ui/button";
import { useToast } from "@/ui/use-toast";
import { IS_SENTRY_ENABLED } from "@/config";
import usePlaygroundStore from "@/store/PlaygroundStore";
import {
  PLAYGROUND_OUTPUT_COLUMNS_ORDER_KEY,
  PLAYGROUND_OUTPUT_COLUMNS_WIDTH_KEY,
  PLAYGROUND_OUTPUT_HIDDEN_COLUMNS_KEY,
  PLAYGROUND_OUTPUT_LEFT_PANEL_WIDTH_KEY,
  PLAYGROUND_SELECTED_DATASET_VERSION_KEY,
} from "@/constants/llm";

const REPORT_ISSUE_URL = "https://github.com/comet-ml/opik/issues/new";

const SAVED_PLAYGROUND_KEYS = [
  PLAYGROUND_SELECTED_DATASET_VERSION_KEY,
  PLAYGROUND_OUTPUT_COLUMNS_WIDTH_KEY,
  PLAYGROUND_OUTPUT_LEFT_PANEL_WIDTH_KEY,
  PLAYGROUND_OUTPUT_HIDDEN_COLUMNS_KEY,
  PLAYGROUND_OUTPUT_COLUMNS_ORDER_KEY,
];

export const clearSavedPlaygroundState = () => {
  // Writing the initial state first replaces the broken one in memory; zustand persists
  // that write, so the storage entry is removed afterwards rather than before.
  usePlaygroundStore.setState(usePlaygroundStore.getInitialState(), true);
  usePlaygroundStore.persist.clearStorage();
  SAVED_PLAYGROUND_KEYS.forEach((key) => localStorage.removeItem(key));
};

const buildErrorDetails = (error: Error, componentStack: string) => {
  const title = `${error.name}: ${error.message}`;
  const stack = error.stack ?? "";

  return [
    // Chrome's stack already starts with the title; Firefox's and Safari's don't.
    stack.startsWith(title) ? "" : title,
    stack,
    componentStack ? `Component stack:${componentStack}` : "",
    `URL: ${window.location.href}`,
    `User agent: ${navigator.userAgent}`,
  ]
    .filter(Boolean)
    .join("\n\n");
};

type PlaygroundCrashFallbackProps = {
  error: Error;
  componentStack: string;
  onReset: () => void;
};

const PlaygroundCrashFallback: React.FC<PlaygroundCrashFallbackProps> = ({
  error,
  componentStack,
  onReset,
}) => {
  const { toast } = useToast();

  const handleCopyDetails = useCallback(async () => {
    try {
      await copy(buildErrorDetails(error, componentStack));
      toast({ description: "Error details copied" });
    } catch {
      toast({
        description:
          "Couldn't copy the error details: the browser blocked the clipboard",
        variant: "destructive",
      });
    }
  }, [error, componentStack, toast]);

  return (
    <div className="flex size-full flex-col items-center justify-center gap-4 p-6 text-center">
      <img src={somethingWentWrongImage} alt="" width="200px" />
      <h2 className="comet-title-m">The playground crashed</h2>
      <p className="comet-body-s max-w-xl text-muted-slate">
        This can happen when the playground&apos;s saved state is broken.
        Resetting clears the prompts, outputs and dataset selection that the
        playground keeps in this browser. Your prompt library, datasets and
        experiments are not affected.
      </p>
      <pre className="comet-code max-w-xl whitespace-pre-wrap break-words rounded-md bg-muted px-3 py-2 text-left text-xs">
        {error.message || error.name}
      </pre>
      <div className="grid grid-cols-1 gap-2 lg:grid-cols-3">
        <Button size="sm" onClick={onReset}>
          <RotateCcw className="mr-1.5 size-3.5" />
          Reset playground
        </Button>
        <Button size="sm" variant="outline" onClick={handleCopyDetails}>
          <Copy className="mr-1.5 size-3.5" />
          Copy details
        </Button>
        <Button size="sm" variant="outline" asChild>
          <a href={REPORT_ISSUE_URL} target="_blank" rel="noopener noreferrer">
            <ExternalLink className="mr-1.5 size-3.5" />
            Report on GitHub
          </a>
        </Button>
      </div>
    </div>
  );
};

type PlaygroundErrorBoundaryProps = {
  children: React.ReactNode;
};

type PlaygroundErrorBoundaryState = {
  error: Error | null;
  componentStack: string;
};

class PlaygroundErrorBoundary extends React.Component<
  PlaygroundErrorBoundaryProps,
  PlaygroundErrorBoundaryState
> {
  state: PlaygroundErrorBoundaryState = { error: null, componentStack: "" };

  static getDerivedStateFromError(error: Error) {
    return { error };
  }

  componentDidCatch(error: Error, info: React.ErrorInfo) {
    this.setState({ componentStack: info.componentStack ?? "" });

    // This boundary stops the error before the app-level Sentry boundary sees it.
    if (IS_SENTRY_ENABLED) {
      Sentry.captureException(error, {
        contexts: { react: { componentStack: info.componentStack } },
      });
    }
  }

  handleReset = () => {
    clearSavedPlaygroundState();
    this.setState({ error: null, componentStack: "" });
  };

  render() {
    const { error, componentStack } = this.state;

    if (error) {
      return (
        <PlaygroundCrashFallback
          error={error}
          componentStack={componentStack}
          onReset={this.handleReset}
        />
      );
    }

    return this.props.children;
  }
}

export default PlaygroundErrorBoundary;
