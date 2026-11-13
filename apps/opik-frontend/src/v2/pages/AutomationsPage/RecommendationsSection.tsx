import React, { useState } from "react";
import isPlainObject from "lodash/isPlainObject";
import useRecommendationsList from "@/api/automations/useRecommendationsList";
import useRecommendationDismissMutation from "@/api/automations/useRecommendationDismissMutation";
import useAutomationUpdateMutation from "@/api/automations/useAutomationUpdateMutation";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import Loader from "@/shared/Loader/Loader";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { Button } from "@/ui/button";
import { Tag, TagProps } from "@/ui/tag";
import { useToast } from "@/ui/use-toast";
import {
  AutomationUpdate,
  Recommendation,
  RecommendationKind,
  WorkflowOverrides,
} from "@/types/event-automations";

const KIND_VARIANT: Record<RecommendationKind, TagProps["variant"]> = {
  setup: "blue",
  tune: "yellow",
  review: "purple",
  remove: "red",
};

// The operations this UI knows how to send; anything else stays advice.
const APPLY_OPERATIONS = [
  "workflow.enable",
  "automation.update",
  "automation.disable",
];

const formatEvidence = (value: unknown): string => {
  if (value == null) return "–";
  if (typeof value !== "object") return String(value);
  if (Array.isArray(value)) {
    if (value.length === 0) return "none";
    return value.length <= 3
      ? value.map(formatEvidence).join(", ")
      : `${value.length} items`;
  }
  return JSON.stringify(value);
};

type RecommendationsSectionProps = {
  projectId: string;
  onEnable: (recipe: string, suggested?: WorkflowOverrides) => void;
};

const RecommendationsSection: React.FC<RecommendationsSectionProps> = ({
  projectId,
  onEnable,
}) => {
  const { data, isPending, isError } = useRecommendationsList({ projectId });
  const dismiss = useRecommendationDismissMutation();
  const update = useAutomationUpdateMutation();
  const { toast } = useToast();
  const [confirming, setConfirming] = useState<Recommendation | null>(null);

  const refuse = (description: string) =>
    toast({ title: "Cannot apply", description, variant: "destructive" });

  const handleApply = (recommendation: Recommendation) => {
    const apply = recommendation.apply!;
    if (apply.operation !== "workflow.enable") {
      setConfirming(recommendation);
      return;
    }
    const { recipe, overrides } = apply.data;
    if (typeof recipe !== "string") {
      refuse("This recommendation names no recipe.");
      return;
    }
    onEnable(
      recipe,
      isPlainObject(overrides) ? (overrides as WorkflowOverrides) : undefined,
    );
  };

  const handleConfirm = () => {
    if (!confirming?.apply) return;
    const { operation, data: payload } = confirming.apply;
    const { id, ...rest } = payload;
    if (typeof id !== "string") {
      refuse("This recommendation names no automation.");
      return;
    }
    update.mutate({
      automationId: id,
      update:
        operation === "automation.disable"
          ? { enabled: false }
          : (rest as AutomationUpdate),
    });
  };

  let body: React.ReactNode;
  if (isPending) {
    body = <Loader className="min-h-24" />;
  } else if (isError || !data) {
    body = (
      <p className="comet-body-s text-muted-slate">
        Recommendations could not be read.
      </p>
    );
  } else if (data.length === 0) {
    body = <p className="comet-body-s text-muted-slate">Nothing to suggest.</p>;
  } else {
    body = (
      <ul className="divide-y divide-border rounded-md border border-border bg-background">
        {data.map((recommendation) => {
          const canApply =
            !!recommendation.apply &&
            APPLY_OPERATIONS.includes(recommendation.apply.operation);
          const blocked = recommendation.blocked_until;
          return (
            <li key={recommendation.id} className="flex items-start gap-3 p-3">
              <Tag
                variant={KIND_VARIANT[recommendation.kind] ?? "gray"}
                className="mt-0.5 shrink-0"
              >
                {recommendation.kind}
              </Tag>
              <div className="flex min-w-0 flex-1 flex-col gap-1.5">
                <p className="comet-body-s">{recommendation.reason}</p>
                <div className="flex flex-wrap gap-1">
                  {Object.entries(recommendation.evidence ?? {}).map(
                    ([key, value]) => (
                      <Tag key={key} variant="gray" size="sm">
                        {key}: {formatEvidence(value)}
                      </Tag>
                    ),
                  )}
                  {blocked && (
                    <Tag variant="orange" size="sm">
                      Blocked until: {blocked}
                    </Tag>
                  )}
                </div>
              </div>
              <div className="flex shrink-0 gap-2">
                {canApply && (
                  <TooltipWrapper
                    content={blocked ? `Blocked until: ${blocked}` : undefined}
                  >
                    <span>
                      <Button
                        size="xs"
                        disabled={!!blocked || update.isPending}
                        onClick={() => handleApply(recommendation)}
                      >
                        Apply
                      </Button>
                    </span>
                  </TooltipWrapper>
                )}
                <Button
                  size="xs"
                  variant="outline"
                  disabled={dismiss.isPending}
                  onClick={() =>
                    dismiss.mutate({
                      recommendationId: recommendation.id,
                      projectId,
                    })
                  }
                >
                  Dismiss
                </Button>
              </div>
            </li>
          );
        })}
      </ul>
    );
  }

  return (
    <section>
      <h2 className="comet-body-accented">Recommendations</h2>
      <p className="comet-body-s mb-3 text-muted-slate">
        Ranked next steps for this project. Applying one sends the change it
        carries; dismissing hides it for 30 days.
      </p>
      {body}
      {confirming?.apply && (
        <ConfirmDialog
          open
          setOpen={(open) => !open && setConfirming(null)}
          title={
            confirming.apply.operation === "automation.disable"
              ? "Disable this automation?"
              : "Update this automation?"
          }
          description={
            <div className="flex flex-col gap-2">
              <span>{confirming.reason}</span>
              <pre className="comet-code max-h-60 overflow-auto rounded-md bg-primary-foreground p-3 text-xs">
                {JSON.stringify(confirming.apply.data, null, 2)}
              </pre>
            </div>
          }
          confirmText={
            confirming.apply.operation === "automation.disable"
              ? "Disable"
              : "Update"
          }
          confirmButtonVariant={
            confirming.apply.operation === "automation.disable"
              ? "destructive"
              : "default"
          }
          onConfirm={handleConfirm}
        />
      )}
    </section>
  );
};

export default RecommendationsSection;
