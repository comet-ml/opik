import React, { useState } from "react";
import { Clock, FilePen, Pencil, User } from "lucide-react";

import { ConfigHistoryItem } from "@/types/agent-configs";
import { formatDate, getTimeFromNow } from "@/lib/date";
import Loader from "@/shared/Loader/Loader";
import { Card } from "@/ui/card";
import ChangeStagePopover from "./ChangeStagePopover";
import BlueprintValuesList from "@/v1/pages-shared/traces/ConfigurationTab/BlueprintValuesList";
import BlueprintDiffDialog from "./BlueprintDiffDialog/BlueprintDiffDialog";
import { generateBlueprintDescription } from "@/utils/agent-configurations";
import { Button } from "@/ui/button";
import useAgentConfigById from "@/api/agent-configs/useAgentConfigById";
import useTracesList from "@/api/traces/useTracesList";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import NavigationTag from "@/shared/NavigationTag/NavigationTag";
import { RESOURCE_TYPE } from "@/shared/ResourceLink/ResourceLink";
import { COLUMN_TYPE } from "@/types/shared";
import { Separator } from "@/ui/separator";
import DiffVersionPopover from "./DiffVersionPopover";
import ConfigTagList from "./ConfigTagList";

type ConfigurationDetailViewProps = {
  item: ConfigHistoryItem;
  projectId: string;
  versions: ConfigHistoryItem[];
  onEdit: () => void;
};

const ConfigurationDetailView: React.FC<ConfigurationDetailViewProps> = ({
  item,
  projectId,
  versions,
  onEdit,
}) => {
  const { data: agentConfig, isPending } = useAgentConfigById({
    blueprintId: item.id,
  });

  const { data: tracesData } = useTracesList({
    projectId,
    filters: [
      {
        id: "agent_configuration_blueprint_id",
        field: "metadata",
        type: COLUMN_TYPE.dictionary,
        operator: "=",
        key: "agent_configuration._blueprint_id",
        value: item.id,
      },
    ],
    page: 1,
    size: 1,
  });

  const hasTraces = (tracesData?.total ?? 0) > 0;

  const [diffOpen, setDiffOpen] = useState(false);
  const [diffBase, setDiffBase] = useState<{
    label: string;
    blueprintId: string;
  } | null>(null);

  const handleSelectDiffVersion = (versionItem: ConfigHistoryItem) => {
    setDiffBase({
      label: versionItem.name,
      blueprintId: versionItem.id,
    });
    setDiffOpen(true);
  };

  const description =
    item.description || generateBlueprintDescription(item.values);

  return (
    <>
      <Card className="mx-6 my-4 p-6">
        <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
          <div className="flex flex-wrap items-center gap-2">
            <h2 className="comet-title-m">{item.name}</h2>
            <ConfigTagList tags={item.tags} maxWidth={200} />
            {versions.length > 1 && (
              <DiffVersionPopover
                currentItemId={item.id}
                versions={versions}
                onSelectVersion={handleSelectDiffVersion}
              />
            )}
          </div>
          <div className="flex items-center gap-2">
            <ChangeStagePopover item={item} projectId={projectId} />
            {hasTraces && (
              <NavigationTag
                id={projectId}
                name="Go to traces"
                resource={RESOURCE_TYPE.traces}
                iconSize={3.5}
                className="[&>div]:text-foreground"
                size="lg"
                search={{
                  traces_filters: [
                    {
                      id: "agent_configuration_blueprint_id",
                      field: "metadata",
                      type: COLUMN_TYPE.dictionary,
                      operator: "=",
                      key: "agent_configuration._blueprint_id",
                      value: item.id,
                    },
                  ],
                }}
              />
            )}
            <Button size="xs" variant="outline" onClick={onEdit}>
              <Pencil className="mr-1.5 size-3.5" />
              Edit configuration
            </Button>
          </div>
        </div>
        <p className="comet-body-s flex w-full min-w-0 items-start gap-1 overflow-hidden text-light-slate">
          <FilePen className="mt-1 size-3 shrink-0" />
          <TooltipWrapper content={description}>
            <span className="w-fit max-w-full truncate">{description}</span>
          </TooltipWrapper>
        </p>
        <div className="comet-body-s mt-1 flex items-center gap-1 text-light-slate">
          <Clock className="size-3 shrink-0" />
          <TooltipWrapper
            content={`${formatDate(item.created_at, {
              utc: true,
              includeSeconds: true,
            })} UTC`}
          >
            <span>{getTimeFromNow(item.created_at)}</span>
          </TooltipWrapper>
          <User className="ml-1.5 size-3.5 shrink-0" />
          <span>{item.created_by}</span>
        </div>

        <Separator className="mb-2 mt-4" />

        {isPending ? (
          <Loader />
        ) : (
          <BlueprintValuesList values={agentConfig?.values ?? []} />
        )}
      </Card>

      {diffBase && (
        <BlueprintDiffDialog
          open={diffOpen}
          setOpen={setDiffOpen}
          base={{
            label: diffBase.label,
            blueprintId: diffBase.blueprintId,
          }}
          diff={{
            label: item.name,
            blueprintId: item.id,
          }}
        />
      )}
    </>
  );
};

export default ConfigurationDetailView;
