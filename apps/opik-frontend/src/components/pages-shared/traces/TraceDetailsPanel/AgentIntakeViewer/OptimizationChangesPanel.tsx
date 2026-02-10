import React, { useState, useCallback } from "react";
import {
  Check,
  ChevronDown,
  ChevronRight,
  Loader2,
  MessageSquare,
  FileText,
  SlidersHorizontal,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Tag } from "@/components/ui/tag";
import { PromptChange, DiffLine, DiffChange, ScalarChange } from "@/types/agent-intake";
import {
  commitOptimization,
  CommitResult,
} from "@/api/agent-intake/useOptimizeStreaming";

type OptimizationChangesPanelProps = {
  optimizationId: string;
  promptChanges: PromptChange[];
  scalarChanges?: ScalarChange[];
  onCommitComplete: (result: CommitResult) => void;
  onDiscard: () => void;
};

const DiffLineView: React.FC<{ line: DiffLine }> = ({ line }) => {
  const prefix = line.type === "deletion" ? "- " : line.type === "addition" ? "+ " : "  ";
  return (
    <div
      className={cn({
        "text-red-600 line-through opacity-75": line.type === "deletion",
        "text-green-700": line.type === "addition",
        "text-muted-slate": line.type === "context",
      })}
    >
      {prefix}
      {line.content}
    </div>
  );
};

const DiffChangeView: React.FC<{ diffChange: DiffChange; isChat: boolean }> = ({
  diffChange,
  isChat,
}) => {
  return (
    <div className="space-y-1">
      {isChat && diffChange.role && (
        <div className="comet-body-xs font-medium text-muted-slate">
          {diffChange.role} message:
        </div>
      )}
      <div className="rounded bg-muted/30 p-2 font-mono text-xs">
        {diffChange.diff.map((line, idx) => (
          <DiffLineView key={idx} line={line} />
        ))}
      </div>
    </div>
  );
};

const PromptDiff: React.FC<{
  original: PromptChange["original"];
  modified: PromptChange["modified"];
  diff?: DiffChange[];
}> = ({ original, modified, diff }) => {
  const isChat = modified.type === "chat";

  if (diff && diff.length > 0) {
    return (
      <div className="space-y-3">
        {diff.map((diffChange, idx) => (
          <DiffChangeView key={idx} diffChange={diffChange} isChat={isChat} />
        ))}
      </div>
    );
  }

  if (isChat && modified.messages) {
    return (
      <div className="space-y-3">
        {modified.messages.map((msg, idx) => {
          const originalMsg = original.messages?.[idx];
          const hasChanges = originalMsg?.content !== msg.content;

          return (
            <div key={idx} className="space-y-1">
              <div className="comet-body-xs font-medium text-muted-slate">
                {msg.role} message:
              </div>
              {hasChanges ? (
                <div className="space-y-1 rounded bg-muted/30 p-2 font-mono text-xs">
                  {originalMsg && (
                    <div className="text-red-600 line-through opacity-60">
                      {originalMsg.content.split("\n").map((line, i) => (
                        <div key={i}>- {line}</div>
                      ))}
                    </div>
                  )}
                  <div className="text-green-700">
                    {msg.content.split("\n").map((line, i) => (
                      <div key={i}>+ {line}</div>
                    ))}
                  </div>
                </div>
              ) : (
                <div className="rounded bg-muted/20 p-2 text-xs text-muted-slate italic">
                  (no changes)
                </div>
              )}
            </div>
          );
        })}
      </div>
    );
  }

  const hasChanges = original.template !== modified.template;
  return (
    <div className="space-y-1">
      {hasChanges ? (
        <div className="space-y-1 rounded bg-muted/30 p-2 font-mono text-xs">
          {original.template && (
            <div className="text-red-600 line-through opacity-60">
              {original.template.split("\n").map((line, i) => (
                <div key={i}>- {line}</div>
              ))}
            </div>
          )}
          {modified.template && (
            <div className="text-green-700">
              {modified.template.split("\n").map((line, i) => (
                <div key={i}>+ {line}</div>
              ))}
            </div>
          )}
        </div>
      ) : (
        <div className="rounded bg-muted/20 p-2 text-xs text-muted-slate italic">
          (no changes)
        </div>
      )}
    </div>
  );
};

const formatScalarValue = (value: unknown): string => {
  if (value === null || value === undefined) return "null";
  if (typeof value === "number") {
    return Number.isInteger(value) ? String(value) : value.toFixed(4);
  }
  if (typeof value === "boolean") return value ? "true" : "false";
  if (typeof value === "string") return `"${value}"`;
  return JSON.stringify(value);
};

const ScalarChangeItem: React.FC<{
  change: ScalarChange;
  isChecked: boolean;
  onToggle: () => void;
}> = ({ change, isChecked, onToggle }) => {
  const [isExpanded, setIsExpanded] = useState(false);

  return (
    <div className="rounded-lg border bg-background">
      <div className="flex items-center gap-3 p-3">
        <Checkbox
          checked={isChecked}
          onCheckedChange={onToggle}
          id={`scalar-${change.key}`}
        />
        <button
          type="button"
          className="flex flex-1 items-center gap-2"
          onClick={() => setIsExpanded(!isExpanded)}
        >
          {isExpanded ? (
            <ChevronDown className="size-4 text-muted-slate" />
          ) : (
            <ChevronRight className="size-4 text-muted-slate" />
          )}
          <span className="comet-body-s font-medium text-foreground">
            {change.key}
          </span>
          <Tag variant="gray" size="sm">
            <SlidersHorizontal className="mr-1 size-3" />
            scalar
          </Tag>
        </button>
      </div>
      {isExpanded && (
        <div className="border-t px-3 pb-3 pt-2">
          <div className="rounded bg-muted/30 p-2 font-mono text-xs">
            <div className="text-red-600 line-through opacity-75">
              - {formatScalarValue(change.original_value)}
            </div>
            <div className="text-green-700">
              + {formatScalarValue(change.modified_value)}
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

const PromptChangeItem: React.FC<{
  change: PromptChange;
  isChecked: boolean;
  onToggle: () => void;
}> = ({ change, isChecked, onToggle }) => {
  const [isExpanded, setIsExpanded] = useState(false);
  const isChat = change.modified.type === "chat";

  return (
    <div className="rounded-lg border bg-background">
      <div className="flex items-center gap-3 p-3">
        <Checkbox
          checked={isChecked}
          onCheckedChange={onToggle}
          id={`prompt-${change.prompt_name}`}
        />
        <button
          type="button"
          className="flex flex-1 items-center gap-2"
          onClick={() => setIsExpanded(!isExpanded)}
        >
          {isExpanded ? (
            <ChevronDown className="size-4 text-muted-slate" />
          ) : (
            <ChevronRight className="size-4 text-muted-slate" />
          )}
          <span className="comet-body-s font-medium text-foreground">
            {change.prompt_name}
          </span>
          <Tag variant={isChat ? "blue" : "purple"} size="sm">
            {isChat ? (
              <>
                <MessageSquare className="mr-1 size-3" />
                chat
              </>
            ) : (
              <>
                <FileText className="mr-1 size-3" />
                text
              </>
            )}
          </Tag>
        </button>
      </div>
      {isExpanded && (
        <div className="border-t px-3 pb-3 pt-2">
          <PromptDiff original={change.original} modified={change.modified} diff={change.diff} />
        </div>
      )}
    </div>
  );
};

const OptimizationChangesPanel: React.FC<OptimizationChangesPanelProps> = ({
  optimizationId,
  promptChanges,
  scalarChanges = [],
  onCommitComplete,
  onDiscard,
}) => {
  const [checkedState, setCheckedState] = useState<Record<string, boolean>>(
    () => {
      const state: Record<string, boolean> = {};
      promptChanges.forEach((change) => {
        state[`prompt:${change.prompt_name}`] = true;
      });
      scalarChanges.forEach((change) => {
        state[`scalar:${change.key}`] = true;
      });
      return state;
    }
  );
  const [isCommitting, setIsCommitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const selectedCount = Object.values(checkedState).filter(Boolean).length;

  const handleTogglePrompt = useCallback((promptName: string) => {
    setCheckedState((prev) => ({
      ...prev,
      [`prompt:${promptName}`]: !prev[`prompt:${promptName}`],
    }));
  }, []);

  const handleToggleScalar = useCallback((key: string) => {
    setCheckedState((prev) => ({
      ...prev,
      [`scalar:${key}`]: !prev[`scalar:${key}`],
    }));
  }, []);

  const handleSave = useCallback(async () => {
    const selectedPrompts = Object.entries(checkedState)
      .filter(([key, checked]) => checked && key.startsWith("prompt:"))
      .map(([key]) => key.replace("prompt:", ""));

    const selectedScalars = Object.entries(checkedState)
      .filter(([key, checked]) => checked && key.startsWith("scalar:"))
      .map(([key]) => key.replace("scalar:", ""));

    if (selectedPrompts.length === 0 && selectedScalars.length === 0) return;

    setIsCommitting(true);
    setError(null);

    try {
      const result = await commitOptimization({
        optimization_id: optimizationId,
        prompt_names: selectedPrompts,
        scalar_keys: selectedScalars,
        project_id: "default",
        metadata: { optimizer_generated: true },
      });
      onCommitComplete(result);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setIsCommitting(false);
    }
  }, [checkedState, optimizationId, onCommitComplete]);

  const totalChanges = promptChanges.length + scalarChanges.length;

  return (
    <div className="rounded-lg border bg-green-50">
      <div className="border-b border-green-200 px-4 py-3">
        <div className="comet-body-s-accented mb-1 text-green-800">
          Optimization Complete!
        </div>
        <div className="comet-body-s text-green-700">
          {totalChanges} {totalChanges === 1 ? "value was" : "values were"} modified to pass all
          assertions.
          {promptChanges.length > 0 && scalarChanges.length > 0 && (
            <span className="text-green-600">
              {" "}({promptChanges.length} prompt{promptChanges.length !== 1 ? "s" : ""}, {scalarChanges.length} scalar{scalarChanges.length !== 1 ? "s" : ""})
            </span>
          )}
        </div>
      </div>

      <div className="space-y-2 p-4">
        {scalarChanges.map((change) => (
          <ScalarChangeItem
            key={change.key}
            change={change}
            isChecked={checkedState[`scalar:${change.key}`]}
            onToggle={() => handleToggleScalar(change.key)}
          />
        ))}
        {promptChanges.map((change) => (
          <PromptChangeItem
            key={change.prompt_name}
            change={change}
            isChecked={checkedState[`prompt:${change.prompt_name}`]}
            onToggle={() => handleTogglePrompt(change.prompt_name)}
          />
        ))}
      </div>

      {error && (
        <div className="mx-4 mb-4 rounded border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
          {error}
        </div>
      )}

      <div className="flex items-center justify-end gap-2 border-t border-green-200 px-4 py-3">
        <Button variant="outline" size="sm" onClick={onDiscard} disabled={isCommitting}>
          Discard All
        </Button>
        <Button
          size="sm"
          onClick={handleSave}
          disabled={selectedCount === 0 || isCommitting}
        >
          {isCommitting ? (
            <>
              <Loader2 className="mr-1.5 size-3.5 animate-spin" />
              Saving...
            </>
          ) : (
            `Save Selected (${selectedCount})`
          )}
        </Button>
      </div>
    </div>
  );
};

export default OptimizationChangesPanel;
