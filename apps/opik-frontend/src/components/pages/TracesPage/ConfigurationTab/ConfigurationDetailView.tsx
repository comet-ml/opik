import React, { useEffect, useState } from "react";
import {
  Clock,
  FileText,
  Hash,
  Pencil,
  Rocket,
  RotateCcw,
  ToggleLeft,
  Type,
  User,
  FilePen,
  FileTerminal,
} from "lucide-react";

import {
  ConfigHistoryItem,
  EnrichedBlueprintValue,
} from "@/types/agent-configs";
import { getTimeFromNow } from "@/lib/date";
import { formatNumericData } from "@/lib/utils";
import ColoredTag from "@/components/shared/ColoredTag/ColoredTag";
import Loader from "@/components/shared/Loader/Loader";
import { Card } from "@/components/ui/card";
import ProdTag from "./ProdTag";
import {
  getVersionDescription,
  isProdTag,
  sortTags,
} from "@/utils/agent-configurations";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Switch } from "@/components/ui/switch";
import useAgentConfigById from "@/api/agent-configs/useAgentConfigById";
import useAgentConfigEnvsMutation from "@/api/agent-configs/useAgentConfigEnvsMutation";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";

type ConfigurationDetailViewProps = {
  item: ConfigHistoryItem;
  version: number;
  projectId: string;
  isLatest: boolean;
};

const TYPE_CONFIG: Record<
  string,
  { icon: React.ComponentType<{ className?: string }>; color: string }
> = {
  number: { icon: Hash, color: "#0EA5E9" },
  boolean: { icon: ToggleLeft, color: "#10B981" },
  Prompt: { icon: FileTerminal, color: "#DB46EF" },
  string: { icon: Type, color: "#7C3AED" },
};

const FALLBACK_TYPE_CONFIG = { icon: Type, color: "#6B7280" };

const TypeIconBadge: React.FC<{ type: string }> = ({ type }) => {
  const { icon: Icon, color } = TYPE_CONFIG[type] ?? FALLBACK_TYPE_CONFIG;
  return (
    <span
      className="flex size-5 shrink-0 items-center justify-center rounded"
      style={{ backgroundColor: color }}
    >
      <Icon className="size-3.5 text-white" />
    </span>
  );
};

const getTypeIcon = (type: string) => <TypeIconBadge type={type} />;

const renderTag = (tag: string) =>
  isProdTag(tag) ? (
    <ProdTag key={tag} />
  ) : (
    <ColoredTag key={tag} label={tag} />
  );

const renderValue = (v: EnrichedBlueprintValue) => {
  switch (v.type) {
    case "number": {
      const num = Number(v.value);
      return (
        <div className="comet-body-s whitespace-pre-wrap break-words rounded-md border bg-primary-foreground p-3 text-foreground">
          {isNaN(num) ? v.value : formatNumericData(num)}
        </div>
      );
    }
    case "boolean": {
      const isTruthy = v.value === "true";
      return (
        <div className="comet-body-s whitespace-pre-wrap break-words rounded-md border bg-primary-foreground p-3 text-foreground">
          {isTruthy ? "true" : "false"}
        </div>
      );
    }
    case "Prompt":
      return (
        <div className="flex items-center gap-1.5 overflow-hidden">
          <FileTerminal className="size-3.5 shrink-0 text-muted-slate" />
          <div className="flex flex-col overflow-hidden">
            <span className="comet-body-s truncate">
              {v.promptName ?? v.value}
            </span>
            <span className="comet-body-xs truncate text-muted-slate">
              {v.value}
            </span>
          </div>
        </div>
      );
    default:
      return (
        <div className="comet-body-s whitespace-pre-wrap break-words rounded-md border bg-primary-foreground p-3 text-foreground">
          {v.value}
        </div>
      );
  }
};

const ConfigurationDetailView: React.FC<ConfigurationDetailViewProps> = ({
  item,
  version,
  projectId,
  isLatest,
}) => {
  const { data: agentConfig, isPending } = useAgentConfigById({
    blueprintId: item.id,
  });

  const [confirmOpen, setConfirmOpen] = useState(false);
  const [isEditing, setIsEditing] = useState(false);
  const [originalValues, setOriginalValues] = useState<Record<string, string>>(
    {},
  );
  const [draftValues, setDraftValues] = useState<Record<string, string>>({});

  // Initialize original + draft values once when agentConfig loads
  useEffect(() => {
    if (agentConfig && Object.keys(originalValues).length === 0) {
      const initial: Record<string, string> = {};
      agentConfig.values
        .filter((v) => v.type !== "Prompt")
        .forEach((v) => {
          initial[v.key] = v.value;
        });
      setOriginalValues(initial);
      setDraftValues(initial);
    }
  }, [agentConfig, originalValues]);

  // Reset edit mode and local state when switching versions
  useEffect(() => {
    setIsEditing(false);
    setOriginalValues({});
    setDraftValues({});
  }, [item.id]);

  const resetField = (key: string) => {
    setDraftValues((prev) => ({ ...prev, [key]: originalValues[key] }));
  };

  const hasChanges = Object.keys(draftValues).some(
    (key) => draftValues[key] !== originalValues[key],
  );

  const { mutate: promoteToProd, isPending: isPromoting } =
    useAgentConfigEnvsMutation();

  const handleConfirmPromote = () => {
    promoteToProd({
      envsRequest: {
        projectId,
        envs: [{ env_name: "prod", blueprint_id: item.id }],
      },
    });
  };

  return (
    <>
      <Card className="mx-6 my-4 p-6">
        {/* Header */}
        <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
          <div className="flex flex-wrap items-center gap-2">
            <h2 className="comet-title-m">v{version}</h2>
            {sortTags(item.tags).map(renderTag)}
          </div>
          <div className="flex items-center gap-2">
            {isEditing ? (
              <>
                <Button
                  size="sm"
                  variant="outline"
                  onClick={() => setIsEditing(false)}
                >
                  Cancel
                </Button>
                <Button size="sm" onClick={() => setIsEditing(false)}>
                  Save
                </Button>
              </>
            ) : (
              <>
                {hasChanges && (
                  <Button
                    size="sm"
                    variant="outline"
                    onClick={() => setDraftValues(originalValues)}
                  >
                    <RotateCcw className="mr-1.5 size-3.5" />
                    Reset all
                  </Button>
                )}
                {isLatest && (
                  <Button
                    size="xs"
                    variant="outline"
                    onClick={() => setIsEditing(true)}
                  >
                    <Pencil className="mr-1.5 size-3.5" />
                    Edit
                  </Button>
                )}
                {!item.tags.some(isProdTag) && (
                  <Button
                    size="xs"
                    variant="outline"
                    onClick={() => setConfirmOpen(true)}
                    disabled={isPromoting}
                  >
                    <Rocket className="mr-1.5 size-3.5" color="#A3E635" />
                    {isPromoting ? "Promoting..." : "Promote to prod"}
                  </Button>
                )}
              </>
            )}
          </div>
        </div>
        <p className="comet-body-s flex items-center gap-1 text-light-slate">
          <FilePen className="size-3 shrink-0" />
          {item.description || getVersionDescription(item.id, item.created_by)}
        </p>
        <div className="comet-body-s mt-1 flex items-center gap-1 text-light-slate">
          <Clock className="size-3 shrink-0" />
          <span>{getTimeFromNow(item.created_at)}</span>
          <User className="size-3.5 ml-1.5 shrink-0" />
          <span>{item.created_by}</span>
        </div>

        {isPending ? (
          <Loader />
        ) : (
          <div className="flex flex-col divide-y">
            {(agentConfig?.values ?? []).map((v) => {
              const isChanged =
                v.type !== "Prompt" &&
                draftValues[v.key] !== undefined &&
                draftValues[v.key] !== originalValues[v.key];
              return (
                <div key={v.key} className="flex flex-col gap-2 py-3">
                  <div className="flex items-center gap-2">
                    {getTypeIcon(v.type)}
                    <span className="comet-body-xs-accented text-foreground">
                      {v.key}
                    </span>
                    {isChanged && (
                      <span className="size-1.5 rounded-full bg-amber-400" />
                    )}
                    {isChanged && (
                      <button
                        className="ml-auto flex items-center gap-1 text-xs text-light-slate hover:text-foreground"
                        onClick={() => resetField(v.key)}
                        title="Reset to original"
                      >
                        <RotateCcw className="size-3" />
                        Reset
                      </button>
                    )}
                  </div>
                  {v.description && (
                    <span className="comet-body-xs text-light-slate">
                      {v.description}
                    </span>
                  )}
                  <div className="overflow-hidden">
                    {isEditing && v.type !== "Prompt" ? (
                      v.type === "boolean" ? (
                        <Switch
                          checked={draftValues[v.key] === "true"}
                          onCheckedChange={(checked) =>
                            setDraftValues((prev) => ({
                              ...prev,
                              [v.key]: String(checked),
                            }))
                          }
                        />
                      ) : (
                        <Input
                          type={v.type === "number" ? "number" : "text"}
                          value={draftValues[v.key] ?? ""}
                          onChange={(e) =>
                            setDraftValues((prev) => ({
                              ...prev,
                              [v.key]: e.target.value,
                            }))
                          }
                        />
                      )
                    ) : (
                      renderValue(
                        v.type !== "Prompt" && draftValues[v.key] !== undefined
                          ? { ...v, value: draftValues[v.key] }
                          : v,
                      )
                    )}
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </Card>

      <ConfirmDialog
        open={confirmOpen}
        setOpen={setConfirmOpen}
        onConfirm={handleConfirmPromote}
        title="Promote to production"
        description={`This will set v${version} as the active configuration for the prod environment. Are you sure you want to continue?`}
        confirmText="Promote to prod"
      />
    </>
  );
};

export default ConfigurationDetailView;
