import React, { useState } from "react";
import {
  Clock,
  FilePen,
  Pencil,
  Rocket,
  User,
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
import BlueprintValuePrompt from "./BlueprintValuePrompt";
import ProdTag from "./ProdTag";
import BlueprintTypeIcon from "./BlueprintTypeIcon";
import {
  getVersionDescription,
  isProdTag,
  sortTags,
} from "@/utils/agent-configurations";
import { Button } from "@/components/ui/button";
import useAgentConfigById from "@/api/agent-configs/useAgentConfigById";
import useAgentConfigEnvsMutation from "@/api/agent-configs/useAgentConfigEnvsMutation";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";

type ConfigurationDetailViewProps = {
  item: ConfigHistoryItem;
  version: number;
  projectId: string;
  isLatest: boolean;
  onEdit: () => void;
};

const renderTag = (tag: string) =>
  isProdTag(tag) ? <ProdTag key={tag} /> : <ColoredTag key={tag} label={tag} />;

const renderValue = (v: EnrichedBlueprintValue) => {
  switch (v.type) {
    case "int":
    case "float": {
      const num = Number(v.value);
      return (
        <div className="comet-body-s whitespace-pre-wrap break-words rounded-md border bg-primary-foreground p-3 text-foreground">
          {isNaN(num) ? v.value : formatNumericData(num)}
        </div>
      );
    }
    case "boolean":
      return (
        <div className="comet-body-s whitespace-pre-wrap break-words rounded-md border bg-primary-foreground p-3 text-foreground">
          {v.value === "true" ? "true" : "false"}
        </div>
      );
    case "prompt":
      return <BlueprintValuePrompt value={v} />;
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
  onEdit,
}) => {
  const { data: agentConfig, isPending } = useAgentConfigById({
    blueprintId: item.id,
  });

  const [confirmOpen, setConfirmOpen] = useState(false);

  const { mutate: promoteToProd, isPending: isPromoting } =
    useAgentConfigEnvsMutation();

  const handleConfirmPromote = () => {
    promoteToProd({
      envsRequest: {
        project_id: projectId,
        envs: [{ env_name: "prod", blueprint_id: item.id }],
      },
    });
  };

  return (
    <>
      <Card className="mx-6 my-4 p-6">
        <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
          <div className="flex flex-wrap items-center gap-2">
            <h2 className="comet-title-m">v{version}</h2>
            {sortTags(item.tags).map(renderTag)}
          </div>
          <div className="flex items-center gap-2">
            {isLatest && (
              <Button size="xs" variant="outline" onClick={onEdit}>
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
            {(agentConfig?.values ?? []).map((v) => (
              <div key={v.key} className="flex flex-col gap-2 py-3">
                <div className="flex items-center gap-2">
                  <BlueprintTypeIcon type={v.type} />
                  <span className="comet-body-xs-accented text-foreground">
                    {v.key}
                  </span>
                </div>
                {v.description && (
                  <span className="comet-body-xs text-light-slate">
                    {v.description}
                  </span>
                )}
                <div className="overflow-hidden">{renderValue(v)}</div>
              </div>
            ))}
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
