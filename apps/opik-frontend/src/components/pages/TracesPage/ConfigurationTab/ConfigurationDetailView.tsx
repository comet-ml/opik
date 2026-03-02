import React, { useState } from "react";
import { FileText } from "lucide-react";

import { ConfigHistoryItem, EnrichedBlueprintValue } from "@/types/optimizer-configs";
import { formatDate } from "@/lib/date";
import { formatNumericData } from "@/lib/utils";
import ColoredTag from "@/components/shared/ColoredTag/ColoredTag";
import Loader from "@/components/shared/Loader/Loader";
import { Card } from "@/components/ui/card";
import { Tag } from "@/components/ui/tag";
import { Button } from "@/components/ui/button";
import useAgentConfigById from "@/api/optimizer-configs/useAgentConfigById";
import useAgentConfigEnvsMutation from "@/api/optimizer-configs/useAgentConfigEnvsMutation";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";

type ConfigurationDetailViewProps = {
  item: ConfigHistoryItem;
  version: number;
  projectId: string;
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
            <span className="comet-body-s truncate">{v.promptName ?? v.value}</span>
            <span className="comet-body-xs truncate text-muted-slate">{v.value}</span>
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
          <Button
            size="sm"
            onClick={() => setConfirmOpen(true)}
            disabled={isPromoting}
          >
            {isPromoting ? "Promoting..." : "Promote to prod"}
          </Button>
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
            {(agentConfig?.values ?? []).map((v) => (
              <Card key={v.key} className="flex flex-col gap-2 p-4">
                <div className="flex items-center gap-2">
                  <span className="comet-body-xs text-muted-slate">{v.key}</span>
                  <Tag size="sm" variant="gray" className="capitalize shrink-0">
                    {v.type}
                  </Tag>
                </div>
                <div className="overflow-hidden">{renderValue(v)}</div>
              </Card>
            ))}
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
