import React, { useEffect, useState } from "react";
import {
  Clock,
  FileText,
  Hash,
  Rocket,
  RotateCcw,
  ToggleLeft,
  Type,
  User,
} from "lucide-react";

import {
  ConfigHistoryItem,
  EnrichedBlueprintValue,
} from "@/types/optimizer-configs";
import { getTimeFromNow } from "@/lib/date";
import { cn, formatNumericData } from "@/lib/utils";
import ColoredTag from "@/components/shared/ColoredTag/ColoredTag";
import Loader from "@/components/shared/Loader/Loader";
import { Card } from "@/components/ui/card";
import { Tag } from "@/components/ui/tag";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Switch } from "@/components/ui/switch";
import useAgentConfigById from "@/api/optimizer-configs/useAgentConfigById";
import useAgentConfigEnvsMutation from "@/api/optimizer-configs/useAgentConfigEnvsMutation";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";

type ConfigurationDetailViewProps = {
  item: ConfigHistoryItem;
  version: number;
  projectId: string;
  isLatest: boolean;
};

const TYPE_ICONS: Record<string, React.ReactNode> = {
  number: <Hash className="size-3.5 shrink-0 text-muted-slate" />,
  boolean: <ToggleLeft className="size-3.5 shrink-0 text-muted-slate" />,
  Prompt: <FileText className="size-3.5 shrink-0 text-muted-slate" />,
  string: <Type className="size-3.5 shrink-0 text-muted-slate" />,
};

const getTypeIcon = (type: string) =>
  TYPE_ICONS[type] ?? <Type className="size-3.5 shrink-0 text-muted-slate" />;

const isProdTag = (tag: string) => /^prod(uction)?$/i.test(tag);

const renderTag = (tag: string) =>
  isProdTag(tag) ? (
    <Tag
      key={tag}
      size="sm"
      variant="green"
      className="inline-flex items-center gap-1"
    >
      <Rocket className="size-3 shrink-0" />
      <span>{tag}</span>
    </Tag>
  ) : (
    <ColoredTag key={tag} label={tag} size="sm" />
  );

const renderValue = (v: EnrichedBlueprintValue) => {
  switch (v.type) {
    case "number": {
      const num = Number(v.value);
      return (
        <code className="comet-code block w-full rounded bg-muted px-3 py-2">
          {isNaN(num) ? v.value : formatNumericData(num)}
        </code>
      );
    }
    case "boolean": {
      const isTruthy = v.value === "true";
      return (
        <code className="comet-code block w-full rounded bg-muted px-3 py-2">
          {isTruthy ? "true" : "false"}
        </code>
      );
    }
    case "Prompt":
      return (
        <div className="flex items-center gap-1.5 overflow-hidden">
          <FileText className="size-3.5 shrink-0 text-muted-slate" />
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
        <code className="comet-code block w-full rounded bg-muted px-3 py-2">
          {v.value}
        </code>
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
            {item.tags.map(renderTag)}
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
                    size="sm"
                    variant="outline"
                    onClick={() => setIsEditing(true)}
                  >
                    Edit
                  </Button>
                )}
                <Button
                  size="sm"
                  onClick={() => setConfirmOpen(true)}
                  disabled={isPromoting}
                >
                  {isPromoting ? "Promoting..." : "Promote to prod"}
                </Button>
              </>
            )}
          </div>
        </div>
        <p className="comet-body-xs text-light-slate">{item.description}</p>
        <div className="comet-body-xs mt-1 flex items-center gap-1 text-light-slate">
          <Clock className="size-3 shrink-0" />
          <span>{getTimeFromNow(item.createdAt)}</span>
          <User className="size-3.5 ml-1.5 shrink-0" />
          <span>{item.createdBy}</span>
        </div>

        {/* Agent config section */}
        <p className="comet-body-s-accented mb-3 mt-6">Agent config</p>
        {isPending ? (
          <Loader />
        ) : (
          <div className="flex flex-col divide-y divide-border">
            {(agentConfig?.values ?? []).map((v) => {
              const isChanged =
                v.type !== "Prompt" &&
                draftValues[v.key] !== undefined &&
                draftValues[v.key] !== originalValues[v.key];
              return (
                <div
                  key={v.key}
                  className={cn(
                    "flex flex-col gap-2 py-3",
                    isChanged ? "border-l-2 border-l-amber-400 pl-3" : "",
                  )}
                >
                  <div className="flex items-center gap-2">
                    {getTypeIcon(v.type)}
                    <div className="flex flex-col">
                      <span className="comet-body-xs text-foreground">
                        {v.key}
                      </span>
                      {v.description && (
                        <span className="comet-body-xs text-muted-slate">
                          {v.description}
                        </span>
                      )}
                    </div>
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
