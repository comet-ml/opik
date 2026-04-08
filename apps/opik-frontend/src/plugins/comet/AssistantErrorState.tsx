import React from "react";
import { AlertCircle } from "lucide-react";
import { SLACK_LINK } from "@/shared/SupportHub/SupportHubSubMenu";

interface AssistantErrorStateProps {
  collapsed: boolean;
  onRetry?: () => void;
  retryCount: number;
}

const CollapsedError: React.FC<
  Pick<AssistantErrorStateProps, "onRetry" | "retryCount">
> = ({ onRetry, retryCount }) => {
  const hasRetriedAndFailed = retryCount > 0;

  return (
    <button
      type="button"
      className="flex size-full flex-col items-center border-l bg-primary-foreground pt-3"
      onClick={onRetry}
      title={
        hasRetriedAndFailed
          ? "Assistant is currently unavailable"
          : "Unable to load assistant. Click to retry"
      }
    >
      <AlertCircle className="size-4 shrink-0 text-destructive" />
      <span className="mt-2 text-xs text-muted-foreground [writing-mode:vertical-lr]">
        {hasRetriedAndFailed
          ? "Assistant is currently unavailable"
          : "Unable to load assistant. Try again later"}
      </span>
    </button>
  );
};

const EscalatedError: React.FC<Pick<AssistantErrorStateProps, "onRetry">> = ({
  onRetry,
}) => (
  <div className="flex size-full flex-col items-center justify-center gap-3 border-l bg-primary-foreground px-6 text-center">
    <AlertCircle className="size-5 text-destructive" />
    <div className="text-sm font-medium text-foreground">
      Assistant is currently unavailable
    </div>
    <p className="text-xs text-muted-foreground">
      Please try again later or{" "}
      <a
        href={SLACK_LINK}
        target="_blank"
        rel="noopener noreferrer"
        className="text-xs text-primary underline underline-offset-2 hover:text-primary-hover"
      >
        get help on Slack
      </a>
    </p>
    <button
      type="button"
      className="mt-1 text-xs text-muted-foreground underline underline-offset-2 hover:text-foreground"
      onClick={onRetry}
    >
      retry
    </button>
  </div>
);

const FirstRetryError: React.FC<Pick<AssistantErrorStateProps, "onRetry">> = ({
  onRetry,
}) => (
  <div className="flex size-full flex-col items-center justify-center gap-3 border-l bg-primary-foreground px-6 text-center">
    <AlertCircle className="size-5 text-destructive" />
    <div className="text-sm font-medium text-foreground">
      Unable to load assistant
    </div>
    <p className="text-xs text-muted-foreground">
      Try again later or{" "}
      <button
        type="button"
        className="inline text-xs text-primary underline underline-offset-2 hover:text-primary-hover"
        onClick={onRetry}
      >
        retry now
      </button>
    </p>
  </div>
);

const AssistantErrorState: React.FC<AssistantErrorStateProps> = ({
  collapsed,
  onRetry,
  retryCount,
}) => {
  if (collapsed)
    return <CollapsedError onRetry={onRetry} retryCount={retryCount} />;
  if (retryCount > 0) return <EscalatedError onRetry={onRetry} />;
  return <FirstRetryError onRetry={onRetry} />;
};

export default AssistantErrorState;
