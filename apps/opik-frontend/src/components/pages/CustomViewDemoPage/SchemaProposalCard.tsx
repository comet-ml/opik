import React from "react";
import { Sparkles, Check, X, Loader2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { SchemaProposal } from "@/types/schema-proposal";
import { cn } from "@/lib/utils";

interface SchemaProposalCardProps {
  proposal: SchemaProposal;
  status: "pending" | "generating";
  onAccept: () => void;
  onReject: () => void;
}

const SchemaProposalCard: React.FC<SchemaProposalCardProps> = ({
  proposal,
  status,
  onAccept,
  onReject,
}) => {
  const isGenerating = status === "generating";
  const isPending = status === "pending";

  return (
    <div className="mb-2 flex justify-start">
      <div
        className={cn(
          "relative min-w-[20%] max-w-[85%] rounded-lg border px-4 py-3",
          isGenerating
            ? "border-blue-200 bg-blue-50 dark:border-blue-800 dark:bg-blue-900/20"
            : "border-purple-200 bg-purple-50 dark:border-purple-800 dark:bg-purple-900/20",
        )}
      >
        <div className="mb-3 flex items-start gap-2">
          <Sparkles
            className={cn(
              "mt-0.5 size-5 shrink-0",
              isGenerating
                ? "text-blue-600 dark:text-blue-400"
                : "text-purple-600 dark:text-purple-400",
            )}
          />
          <div className="flex-1">
            <div
              className={cn(
                "comet-body-s-accented mb-1",
                isGenerating
                  ? "text-blue-900 dark:text-blue-100"
                  : "text-purple-900 dark:text-purple-100",
              )}
            >
              {isGenerating ? "Generating Schema..." : "Generate View Schema?"}
            </div>
            <div
              className={cn(
                "comet-body-s",
                isGenerating
                  ? "text-blue-700 dark:text-blue-300"
                  : "text-purple-700 dark:text-purple-300",
              )}
            >
              {proposal.intentSummary}
            </div>
          </div>
        </div>

        {isGenerating ? (
          <div className="flex items-center gap-2">
            <Loader2 className="size-4 animate-spin text-blue-600 dark:text-blue-400" />
            <span className="comet-body-xs text-blue-600 dark:text-blue-400">
              Please wait while the AI generates your custom view...
            </span>
          </div>
        ) : (
          <div className="flex gap-2">
            <Button
              onClick={onAccept}
              size="sm"
              className="bg-purple-600 hover:bg-purple-700 dark:bg-purple-700 dark:hover:bg-purple-600"
              disabled={!isPending}
            >
              <Check className="mr-1 size-4" />
              Accept
            </Button>
            <Button
              onClick={onReject}
              variant="outline"
              size="sm"
              className="border-purple-300 text-purple-700 hover:bg-purple-100 dark:border-purple-700 dark:text-purple-300 dark:hover:bg-purple-900/30"
              disabled={!isPending}
            >
              <X className="mr-1 size-4" />
              Reject
            </Button>
          </div>
        )}
      </div>
    </div>
  );
};

export default SchemaProposalCard;
