import React, { useMemo, useState } from "react";
import { Clock, FilePen, Pencil, User } from "lucide-react";

import { ConfigHistoryItem } from "@/types/agent-configs";
import { formatDate, getTimeFromNow } from "@/lib/date";
import Loader from "@/shared/Loader/Loader";
import { cn } from "@/lib/utils";
import { Card } from "@/ui/card";
import DeployToPopover from "./DeployToPopover";
import BlueprintValuesList from "@/v2/pages-shared/traces/ConfigurationTab/BlueprintValuesList";
import BlueprintDiffDialog from "./BlueprintDiffDialog/BlueprintDiffDialog";
import { Button } from "@/ui/button";
import useAgentConfigById from "@/api/agent-configs/useAgentConfigById";
import useTracesList from "@/api/traces/useTracesList";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import NavigationTag from "@/shared/NavigationTag/NavigationTag";
import { RESOURCE_TYPE } from "@/shared/ResourceLink/ResourceLink";
import { COLUMN_TYPE } from "@/types/shared";
import { Separator } from "@/ui/separator";
import DiffVersionPopover from "./DiffVersionPopover";
import AgentConfigTagList from "./AgentConfigTagList";
import ExpandAllToggle from "@/v2/pages-shared/agent-configuration/fields/ExpandAllToggle";
import { useFieldsCollapse } from "@/v2/pages-shared/agent-configuration/fields/useFieldsCollapse";
import {
  collectMultiLineKeys,
  hasAnyExpandableField,
} from "@/v2/pages-shared/agent-configuration/fields/blueprintFieldLayout";

const DESCRIPTION_TRUNCATE_LENGTH = 80;

type AgentConfigurationDetailViewProps = {
  item: ConfigHistoryItem;
  projectId: string;
  versions: ConfigHistoryItem[];
  onEdit: () => void;
};

const AgentConfigurationDetailView: React.FC<
  AgentConfigurationDetailViewProps
> = ({ item, projectId, versions, onEdit }) => {
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
  const [comparedVersion, setComparedVersion] = useState<{
    label: string;
    blueprintId: string;
  } | null>(null);
  const [notesExpanded, setNotesExpanded] = useState(false);

  const handleSelectDiffVersion = (versionItem: ConfigHistoryItem) => {
    setComparedVersion({
      label: versionItem.name,
      blueprintId: versionItem.id,
    });
    setDiffOpen(true);
  };

  const isLatestVersion = versions[0]?.id === item.id;

  const description = item.description;

  const descriptionIsLong =
    (description?.length ?? 0) > DESCRIPTION_TRUNCATE_LENGTH;

  const collapsibleKeys = useMemo(
    () => collectMultiLineKeys(agentConfig?.values ?? []),
    [agentConfig],
  );
  const hasExpandableFields = useMemo(
    () => hasAnyExpandableField(agentConfig?.values ?? []),
    [agentConfig],
  );
  const collapseController = useFieldsCollapse({ collapsibleKeys });

  return (
    <>
      <Card className="mx-6 my-4 p-6">
        <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
          <div className="flex flex-wrap items-center gap-2">
            <h2 className="comet-title-m">{item.name}</h2>
            <AgentConfigTagList tags={item.tags} maxWidth={200} />
            {versions.length > 1 && (
              <DiffVersionPopover
                currentItemId={item.id}
                versions={versions}
                onSelectVersion={handleSelectDiffVersion}
              />
            )}
          </div>
          <div className="flex items-center gap-2">
            <DeployToPopover
              item={item}
              projectId={projectId}
              versions={versions}
            />
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
            <TooltipWrapper
              content={
                isLatestVersion
                  ? undefined
                  : "Editing is only available for the latest version"
              }
            >
              <Button
                size="xs"
                variant="outline"
                onClick={onEdit}
                disabled={!isLatestVersion}
                style={!isLatestVersion ? { pointerEvents: "auto" } : undefined}
              >
                <Pencil className="mr-1.5 size-3.5 text-light-slate" />
                Edit configuration
              </Button>
            </TooltipWrapper>
          </div>
        </div>
        {description && (
          <div className="comet-body-s flex w-full min-w-0 items-start gap-1 text-light-slate">
            <FilePen className="mt-1 size-3 shrink-0" />
            <div
              className={cn(
                "flex min-w-0 flex-1 items-baseline gap-1",
                notesExpanded && "flex-wrap",
              )}
            >
              <span
                className={cn(
                  "min-w-0",
                  notesExpanded ? "break-words" : "truncate",
                )}
              >
                {description}
              </span>
              {descriptionIsLong && (
                <button
                  type="button"
                  className="shrink-0 text-light-slate underline"
                  onClick={() => setNotesExpanded((v) => !v)}
                >
                  {notesExpanded ? "Show less" : "Show more"}
                </button>
              )}
            </div>
          </div>
        )}
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
          {hasExpandableFields && (
            <div className="ml-auto text-foreground">
              <ExpandAllToggle controller={collapseController} />
            </div>
          )}
        </div>

        <Separator className="my-4" />

        {isPending ? (
          <Loader />
        ) : (
          <BlueprintValuesList
            values={agentConfig?.values ?? []}
            controller={collapseController}
          />
        )}
      </Card>

      {comparedVersion && (
        <BlueprintDiffDialog
          open={diffOpen}
          setOpen={setDiffOpen}
          base={{
            label: item.name,
            blueprintId: item.id,
          }}
          diff={{
            label: comparedVersion.label,
            blueprintId: comparedVersion.blueprintId,
          }}
        />
      )}
    </>
  );
};

export default AgentConfigurationDetailView;
