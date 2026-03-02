import React, { useEffect, useState } from "react";
import { FileText, RotateCcw } from "lucide-react";

import {
  ConfigHistoryItem,
  EnrichedBlueprintValue,
} from "@/types/optimizer-configs";
import { formatDate } from "@/lib/date";
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

const renderValue = (v: EnrichedBlueprintValue) => {
  switch (v.type) {
    case "number": {
      const num = Number(v.value);
      return (
        <span className="comet-body-s-accented">
          {isNaN(num) ? v.value : formatNumericData(num)}
        </span>
      );
    }
    case "boolean": {
      const isTruthy = v.value === "true";
      return (
        <Tag size="md" variant={isTruthy ? "green" : "gray"}>
          {isTruthy ? "True" : "False"}
        </Tag>
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
      return <span className="comet-body-s truncate">{v.value}</span>;
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
      <div className="px-6 py-4">
        {/* Header */}
        <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
          <div className="flex flex-wrap items-center gap-2">
            <h2 className="comet-title-m">v{version}</h2>
            {item.tags.map((tag) => (
              <ColoredTag key={tag} label={tag} />
            ))}
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
        <p className="comet-body-s text-light-slate">{item.description}</p>
        <p className="comet-body-xs mt-1 text-muted-slate">
          {item.createdBy} &middot; {formatDate(item.createdAt)}
        </p>

        {/* Agent config section */}
        <p className="comet-body-s-accented mb-3 mt-6">Agent config</p>
        {isPending ? (
          <Loader />
        ) : (
          <div className="flex flex-col gap-3">
            {(agentConfig?.values ?? []).map((v) => {
              const isChanged =
                v.type !== "Prompt" &&
                draftValues[v.key] !== undefined &&
                draftValues[v.key] !== originalValues[v.key];
              return (
                <Card
                  key={v.key}
                  className={cn(
                    "flex flex-col gap-2 p-4",
                    isChanged ? "border-l-2 border-l-amber-400" : "",
                  )}
                >
                  <div className="flex items-center gap-2">
                    <span className="comet-body-xs text-muted-slate">
                      {v.key}
                    </span>
                    <Tag
                      size="sm"
                      variant="gray"
                      className="capitalize shrink-0"
                    >
                      {v.type}
                    </Tag>
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
                </Card>
              );
            })}
          </div>
        )}
      </div>

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
