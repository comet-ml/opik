import React, { useState } from "react";
import { useNavigate, useParams } from "@tanstack/react-router";
import { formatDistanceToNow } from "date-fns";
import {
  ArrowLeft,
  Rocket,
  Wrench,
  RotateCcw,
  FileText,
  ChevronDown,
  ChevronRight,
  Plus,
  Minus,
  FileEdit,
  Settings,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import Loader from "@/components/shared/Loader/Loader";
import useVersionDiff, {
  DiffLine,
  VersionChange,
} from "@/api/blueprints/useVersionDiff";
import useDeploymentVersion from "@/api/blueprints/useDeploymentVersion";
import EnvironmentBadge from "./EnvironmentBadge";
import useBlueprintHistory from "@/api/blueprints/useBlueprintHistory";

const changeTypeIcons: Record<string, React.ElementType> = {
  optimizer: Rocket,
  manual: Wrench,
  rollback: RotateCcw,
};

const DiffView: React.FC<{ diff: DiffLine[] }> = ({ diff }) => {
  if (!diff || diff.length === 0) return null;

  return (
    <div className="overflow-x-auto rounded border bg-muted/30 font-mono text-xs">
      {diff.map((line, idx) => (
        <div
          key={idx}
          className={cn("flex px-3 py-0.5", {
            "bg-green-100 text-green-800 dark:bg-green-900/30 dark:text-green-300":
              line.type === "addition",
            "bg-red-100 text-red-800 dark:bg-red-900/30 dark:text-red-300":
              line.type === "deletion",
            "text-muted-slate": line.type === "context",
          })}
        >
          <span className="mr-4 w-10 shrink-0 text-right text-muted-slate/60">
            {line.old_line ?? ""}
          </span>
          <span className="mr-4 w-10 shrink-0 text-right text-muted-slate/60">
            {line.new_line ?? ""}
          </span>
          <span className="mr-2 w-4 shrink-0">
            {line.type === "addition" && "+"}
            {line.type === "deletion" && "-"}
          </span>
          <span className="whitespace-pre-wrap break-all">{line.content}</span>
        </div>
      ))}
    </div>
  );
};

const PromptContent: React.FC<{ value: unknown; name: string }> = ({
  value,
  name,
}) => {
  const [expanded, setExpanded] = useState(true);

  const extractContent = (val: unknown): string => {
    if (typeof val === "string") return val;
    if (typeof val === "object" && val !== null) {
      const obj = val as Record<string, unknown>;
      if ("messages" in obj) {
        let messages: unknown = obj.messages;
        if (typeof messages === "string") {
          try {
            messages = JSON.parse(messages);
          } catch {
            return messages as string;
          }
        }
        if (Array.isArray(messages)) {
          return messages
            .map((m: { role?: string; content?: string }) => `[${m.role}]\n${m.content}`)
            .join("\n\n");
        }
      }
      if ("prompt" in obj) {
        return String(obj.prompt);
      }
      return JSON.stringify(val, null, 2);
    }
    return String(val);
  };

  const content = extractContent(value);

  return (
    <div className="rounded-lg border bg-background">
      <button
        className="flex w-full items-center gap-2 px-4 py-3 text-left hover:bg-muted/30"
        onClick={() => setExpanded(!expanded)}
      >
        {expanded ? (
          <ChevronDown className="size-4 text-muted-slate" />
        ) : (
          <ChevronRight className="size-4 text-muted-slate" />
        )}
        <FileText className="size-4 text-muted-slate" />
        <span className="font-medium">{name}</span>
      </button>
      {expanded && (
        <div className="border-t px-4 py-3">
          <pre className="whitespace-pre-wrap break-words font-mono text-sm text-muted-slate">
            {content}
          </pre>
        </div>
      )}
    </div>
  );
};

const ConfigContent: React.FC<{ value: unknown; name: string }> = ({
  value,
  name,
}) => {
  const [expanded, setExpanded] = useState(true);

  const formatValue = (val: unknown): string => {
    if (val === null) return "null";
    if (typeof val === "boolean") return val ? "true" : "false";
    if (typeof val === "number") return String(val);
    if (typeof val === "string") return val;
    return JSON.stringify(val, null, 2);
  };

  const content = formatValue(value);
  const isSimple = typeof value !== "object" || value === null;

  return (
    <div className="rounded-lg border bg-background">
      <button
        className="flex w-full items-center gap-2 px-4 py-3 text-left hover:bg-muted/30"
        onClick={() => setExpanded(!expanded)}
      >
        {expanded ? (
          <ChevronDown className="size-4 text-muted-slate" />
        ) : (
          <ChevronRight className="size-4 text-muted-slate" />
        )}
        <Settings className="size-4 text-muted-slate" />
        <span className="font-medium">{name}</span>
        {isSimple && (
          <span className="ml-auto font-mono text-sm text-muted-slate">
            {content}
          </span>
        )}
      </button>
      {expanded && !isSimple && (
        <div className="border-t px-4 py-3">
          <pre className="whitespace-pre-wrap break-words font-mono text-sm text-muted-slate">
            {content}
          </pre>
        </div>
      )}
    </div>
  );
};

const ChangedItem: React.FC<{ change: VersionChange }> = ({ change }) => {
  const [expanded, setExpanded] = useState(true);

  const Icon =
    change.type === "added"
      ? Plus
      : change.type === "removed"
        ? Minus
        : FileEdit;
  const colorClass =
    change.type === "added"
      ? "text-green-600"
      : change.type === "removed"
        ? "text-red-600"
        : "text-blue-600";
  const bgClass =
    change.type === "added"
      ? "border-green-200 bg-green-50/50 dark:border-green-800 dark:bg-green-900/20"
      : change.type === "removed"
        ? "border-red-200 bg-red-50/50 dark:border-red-800 dark:bg-red-900/20"
        : "border-blue-200 bg-blue-50/50 dark:border-blue-800 dark:bg-blue-900/20";

  const isConfig = change.category === "config";
  const CategoryIcon = isConfig ? Settings : FileText;

  return (
    <div className={cn("rounded-lg border", bgClass)}>
      <button
        className="flex w-full items-center gap-2 px-4 py-3 text-left"
        onClick={() => setExpanded(!expanded)}
      >
        {change.diff ? (
          expanded ? (
            <ChevronDown className="size-4 text-muted-slate" />
          ) : (
            <ChevronRight className="size-4 text-muted-slate" />
          )
        ) : (
          <span className="size-4" />
        )}
        <Icon className={cn("size-4", colorClass)} />
        <CategoryIcon className="size-3 text-muted-slate" />
        <span className="font-medium">{change.name}</span>
        <span
          className={cn(
            "rounded px-1.5 py-0.5 text-xs font-medium uppercase",
            colorClass,
          )}
        >
          {change.type}
        </span>
        {isConfig && (
          <span className="rounded bg-muted px-1.5 py-0.5 text-xs text-muted-slate">
            config
          </span>
        )}
      </button>
      {expanded && change.diff && (
        <div className="border-t px-4 py-3">
          <DiffView diff={change.diff} />
        </div>
      )}
    </div>
  );
};

type VersionDetailPageProps = {
  blueprintId: string;
  versionNumber: number;
  onBack: () => void;
};

const VersionDetailPage: React.FC<VersionDetailPageProps> = ({
  blueprintId,
  versionNumber,
  onBack,
}) => {
  const { data: version, isLoading: versionLoading } = useDeploymentVersion({
    blueprintId,
    versionNumber,
  });

  const { data: diffData, isLoading: diffLoading } = useVersionDiff({
    blueprintId,
    versionNumber,
    enabled: versionNumber > 1,
  });

  const { data: history } = useBlueprintHistory({
    blueprintId,
    enabled: !!blueprintId,
  });

  if (versionLoading) {
    return <Loader />;
  }

  if (!version) {
    return (
      <div className="py-12 text-center text-muted-slate">
        Version not found
      </div>
    );
  }

  const Icon = changeTypeIcons[version.change_type] || Wrench;
  const timeAgo = formatDistanceToNow(new Date(version.created_at), {
    addSuffix: true,
  });

  const prompts = version.snapshot?.prompts || {};
  const config = version.snapshot?.config || {};
  const promptNames = Object.keys(prompts);
  const configNames = Object.keys(config);
  const totalItems = promptNames.length + configNames.length;

  // Get environments pointing to this version
  const envs = history
    ? Object.entries(history.pointers)
        .filter(([, v]) => v === versionNumber)
        .map(([env]) => env)
    : [];

  const hasDiff = diffData && diffData.changes.length > 0;

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-start gap-4">
        <Button variant="ghost" size="icon" onClick={onBack}>
          <ArrowLeft className="size-5" />
        </Button>

        <div className="flex-1">
          <div className="flex items-center gap-3">
            <div
              className={cn(
                "flex size-10 items-center justify-center rounded-full border-2",
                envs.includes("prod")
                  ? "border-green-500 bg-green-50 text-green-600 dark:bg-green-900/30"
                  : envs.includes("latest")
                    ? "border-blue-500 bg-blue-50 text-blue-600 dark:bg-blue-900/30"
                    : "border-border bg-muted text-muted-slate",
              )}
            >
              <Icon className="size-5" />
            </div>
            <div>
              <div className="flex items-center gap-2">
                <h1 className="text-xl font-semibold">Version {versionNumber}</h1>
                <span className="rounded bg-muted px-2 py-0.5 text-sm text-muted-slate">
                  {version.change_type}
                </span>
                {envs.map((env) => (
                  <EnvironmentBadge key={env} env={env} />
                ))}
              </div>
              <p className="text-sm text-muted-slate">
                {version.change_summary || "No description"} &middot; {timeAgo}
              </p>
            </div>
          </div>
        </div>
      </div>

      {/* Changes section */}
      {versionNumber > 1 && (
        <div className="rounded-lg border bg-background">
          <div className="border-b px-4 py-3">
            <h2 className="font-medium">
              Changes from v{versionNumber - 1}
            </h2>
            {diffData?.summary && (
              <p className="mt-1 text-sm text-muted-slate">
                {diffData.summary.modified > 0 && (
                  <span className="text-blue-600">
                    {diffData.summary.modified} modified
                  </span>
                )}
                {diffData.summary.added > 0 && (
                  <span className="ml-3 text-green-600">
                    {diffData.summary.added} added
                  </span>
                )}
                {diffData.summary.removed > 0 && (
                  <span className="ml-3 text-red-600">
                    {diffData.summary.removed} removed
                  </span>
                )}
                {diffData.summary.prompts_changed > 0 &&
                  diffData.summary.config_changed > 0 && (
                    <span className="ml-3 text-muted-slate/70">
                      ({diffData.summary.prompts_changed} prompt
                      {diffData.summary.prompts_changed !== 1 ? "s" : ""},{" "}
                      {diffData.summary.config_changed} config)
                    </span>
                  )}
                {!diffData.summary.modified &&
                  !diffData.summary.added &&
                  !diffData.summary.removed && <span>No changes</span>}
              </p>
            )}
          </div>
          <div className="p-4">
            {diffLoading ? (
              <div className="py-8 text-center text-sm text-muted-slate">
                Loading diff...
              </div>
            ) : !hasDiff ? (
              <div className="py-8 text-center text-sm text-muted-slate">
                No changes detected from previous version
              </div>
            ) : (
              <div className="space-y-3">
                {diffData.changes.map((change, idx) => (
                  <ChangedItem key={idx} change={change} />
                ))}
              </div>
            )}
          </div>
        </div>
      )}

      {/* Full configuration snapshot */}
      <div className="rounded-lg border bg-background">
        <div className="border-b px-4 py-3">
          <h2 className="font-medium">Configuration Snapshot</h2>
          <p className="mt-1 text-sm text-muted-slate">
            {promptNames.length > 0 && (
              <span>
                {promptNames.length} prompt{promptNames.length !== 1 ? "s" : ""}
              </span>
            )}
            {promptNames.length > 0 && configNames.length > 0 && ", "}
            {configNames.length > 0 && (
              <span>
                {configNames.length} config value
                {configNames.length !== 1 ? "s" : ""}
              </span>
            )}
            {totalItems === 0 && "Empty snapshot"}
          </p>
        </div>
        <div className="space-y-3 p-4">
          {totalItems === 0 ? (
            <div className="py-8 text-center text-sm text-muted-slate">
              No items in this snapshot
            </div>
          ) : (
            <>
              {promptNames.length > 0 && (
                <div className="space-y-3">
                  {promptNames.map((name) => (
                    <PromptContent key={name} name={name} value={prompts[name]} />
                  ))}
                </div>
              )}
              {configNames.length > 0 && (
                <div className="space-y-3">
                  {promptNames.length > 0 && (
                    <div className="my-4 border-t pt-4">
                      <h3 className="mb-3 text-sm font-medium text-muted-slate">
                        Config Values
                      </h3>
                    </div>
                  )}
                  {configNames.map((name) => (
                    <ConfigContent key={name} name={name} value={config[name]} />
                  ))}
                </div>
              )}
            </>
          )}
        </div>
      </div>
    </div>
  );
};

export default VersionDetailPage;
