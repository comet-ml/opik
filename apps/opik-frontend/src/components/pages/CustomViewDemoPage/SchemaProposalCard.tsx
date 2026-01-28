import React from "react";
import {
  Sparkles,
  Check,
  X,
  Loader2,
  CheckCircle2,
  XCircle,
  AlertCircle,
  RotateCw,
  Edit2,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { SchemaProposal } from "@/types/schema-proposal";
import { cn } from "@/lib/utils";

export type ProposalCardStatus =
  | "pending"
  | "generating"
  | "accepted"
  | "rejected";

interface SchemaProposalCardProps {
  proposal: SchemaProposal;
  status: ProposalCardStatus;
  onAccept?: () => void;
  onReject?: () => void;
  error?: string | null;
  onRetry?: () => void;
}

const SchemaProposalCard: React.FC<SchemaProposalCardProps> = ({
  proposal,
  status,
  onAccept,
  onReject,
  error,
  onRetry,
}) => {
  const isGenerating = status === "generating";
  const isPending = status === "pending";
  const isAccepted = status === "accepted";
  const isRejected = status === "rejected";
  const isResolved = isAccepted || isRejected;
  const hasError = Boolean(error) && isGenerating;
  const isUpdateAction = proposal.action === "update_existing";

  // Determine border/background colors based on status and action
  const getColors = () => {
    if (hasError) {
      return "border-destructive/50 bg-destructive/10 dark:border-destructive/50 dark:bg-destructive/10";
    }
    if (isGenerating) {
      return "border-blue-200 bg-blue-50 dark:border-blue-800 dark:bg-blue-900/20";
    }
    if (isAccepted) {
      return "border-green-200 bg-green-50 dark:border-green-800 dark:bg-green-900/20";
    }
    if (isRejected) {
      return "border-gray-200 bg-gray-50 dark:border-gray-700 dark:bg-gray-800/20";
    }
    // Pending state: amber for updates, purple for new
    if (isUpdateAction) {
      return "border-amber-200 bg-amber-50 dark:border-amber-800 dark:bg-amber-900/20";
    }
    return "border-purple-200 bg-purple-50 dark:border-purple-800 dark:bg-purple-900/20";
  };

  // Determine icon colors based on status and action
  const getIconColors = () => {
    if (hasError) return "text-destructive";
    if (isGenerating) return "text-blue-600 dark:text-blue-400";
    if (isAccepted) return "text-green-600 dark:text-green-400";
    if (isRejected) return "text-gray-500 dark:text-gray-400";
    // Pending state: amber for updates, purple for new
    if (isUpdateAction) {
      return "text-amber-600 dark:text-amber-400";
    }
    return "text-purple-600 dark:text-purple-400";
  };

  // Determine title colors based on status and action
  const getTitleColors = () => {
    if (hasError) return "text-destructive";
    if (isGenerating) return "text-blue-900 dark:text-blue-100";
    if (isAccepted) return "text-green-900 dark:text-green-100";
    if (isRejected) return "text-gray-700 dark:text-gray-300";
    // Pending state: amber for updates, purple for new
    if (isUpdateAction) {
      return "text-amber-900 dark:text-amber-100";
    }
    return "text-purple-900 dark:text-purple-100";
  };

  // Determine description colors based on status and action
  const getDescColors = () => {
    if (hasError) return "text-destructive/80";
    if (isGenerating) return "text-blue-700 dark:text-blue-300";
    if (isAccepted) return "text-green-700 dark:text-green-300";
    if (isRejected) return "text-gray-600 dark:text-gray-400";
    // Pending state: amber for updates, purple for new
    if (isUpdateAction) {
      return "text-amber-700 dark:text-amber-300";
    }
    return "text-purple-700 dark:text-purple-300";
  };

  // Determine title text based on status and action
  const getTitle = () => {
    if (isUpdateAction) {
      if (hasError) return "Update Failed";
      if (isGenerating) return "Updating View...";
      if (isAccepted) return "View Updated";
      if (isRejected) return "Update Rejected";
      return "Update Current View?";
    }
    // Generate new action
    if (hasError) return "Generation Failed";
    if (isGenerating) return "Generating View...";
    if (isAccepted) return "View Generated";
    if (isRejected) return "Proposal Rejected";
    return "Generate New View?";
  };

  // Get the icon component based on status and action
  const getIcon = () => {
    if (hasError) {
      return (
        <AlertCircle
          className={cn("mt-0.5 size-5 shrink-0", getIconColors())}
        />
      );
    }
    // Use Edit2 icon for updates, Sparkles for new generation
    if (isUpdateAction && isPending) {
      return (
        <Edit2 className={cn("mt-0.5 size-5 shrink-0", getIconColors())} />
      );
    }
    return (
      <Sparkles className={cn("mt-0.5 size-5 shrink-0", getIconColors())} />
    );
  };

  return (
    <div className="mb-2 flex justify-start">
      <div
        className={cn(
          "relative min-w-[20%] max-w-[85%] rounded-lg border px-4 py-3",
          getColors(),
        )}
      >
        <div className={cn("flex items-start gap-2", !isResolved && "mb-3")}>
          {getIcon()}
          <div className="flex-1">
            <div className={cn("comet-body-s-accented mb-1", getTitleColors())}>
              {getTitle()}
            </div>
            <div className={cn("comet-body-s", getDescColors())}>
              {proposal.intentSummary}
            </div>
          </div>
        </div>

        {/* Error state with retry */}
        {hasError && (
          <div className="space-y-2">
            <div className="comet-body-xs text-destructive/80">{error}</div>
            {onRetry && (
              <Button
                onClick={onRetry}
                variant="outline"
                size="sm"
                className="border-destructive/30 text-destructive hover:bg-destructive/10 hover:text-destructive"
              >
                <RotateCw className="mr-1 size-4" />
                Retry
              </Button>
            )}
          </div>
        )}

        {/* Loading state */}
        {isGenerating && !hasError && (
          <div className="flex items-center gap-2">
            <Loader2 className="size-4 animate-spin text-blue-600 dark:text-blue-400" />
            <span className="comet-body-xs text-blue-600 dark:text-blue-400">
              {isUpdateAction
                ? "Please wait while the AI updates your custom view..."
                : "Please wait while the AI generates your custom view..."}
            </span>
          </div>
        )}

        {isPending && onAccept && onReject && (
          <div className="flex gap-2">
            <Button
              onClick={onAccept}
              size="sm"
              className={cn(
                isUpdateAction
                  ? "bg-amber-600 hover:bg-amber-700 dark:bg-amber-700 dark:hover:bg-amber-600"
                  : "bg-purple-600 hover:bg-purple-700 dark:bg-purple-700 dark:hover:bg-purple-600",
              )}
            >
              <Check className="mr-1 size-4" />
              {isUpdateAction ? "Update" : "Generate"}
            </Button>
            <Button
              onClick={onReject}
              variant="outline"
              size="sm"
              className={cn(
                isUpdateAction
                  ? "border-amber-300 text-amber-700 hover:bg-amber-100 dark:border-amber-700 dark:text-amber-300 dark:hover:bg-amber-900/30"
                  : "border-purple-300 text-purple-700 hover:bg-purple-100 dark:border-purple-700 dark:text-purple-300 dark:hover:bg-purple-900/30",
              )}
            >
              <X className="mr-1 size-4" />
              Cancel
            </Button>
          </div>
        )}

        {isAccepted && (
          <div className="mt-2 flex items-center gap-1.5">
            <CheckCircle2 className="size-4 text-green-600 dark:text-green-400" />
            <span className="comet-body-xs font-medium text-green-600 dark:text-green-400">
              Accepted
            </span>
          </div>
        )}

        {isRejected && (
          <div className="mt-2 flex items-center gap-1.5">
            <XCircle className="size-4 text-gray-500 dark:text-gray-400" />
            <span className="comet-body-xs font-medium text-gray-500 dark:text-gray-400">
              Rejected
            </span>
          </div>
        )}
      </div>
    </div>
  );
};

export default SchemaProposalCard;
