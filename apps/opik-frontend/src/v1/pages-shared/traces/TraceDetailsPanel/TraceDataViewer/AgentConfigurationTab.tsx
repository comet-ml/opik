import React, { useMemo } from "react";
import { FileSliders, GitCommitVertical, Info } from "lucide-react";
import { Link } from "@tanstack/react-router";

import { Span, Trace } from "@/types/traces";
import { BlueprintValue, BlueprintValueType } from "@/types/agent-configs";
import useConfigVersionMap from "@/api/agent-configs/useConfigVersionMap";
import BlueprintValuesList from "@/v1/pages-shared/traces/ConfigurationTab/BlueprintValuesList";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { Tag } from "@/ui/tag";
import { Button } from "@/ui/button";
import { Separator } from "@/ui/separator";
import useAppStore from "@/store/AppStore";
import { AGENT_CONFIGURATION_METADATA_KEY } from "@/utils/agent-configurations";

type AgentConfigurationMetadata = {
  blueprint_id: string;
  values?: Record<
    string,
    { type: BlueprintValueType; value: unknown; description?: string }
  >;
};

export const isAgentConfigurationMetadata = (
  value: unknown,
): value is AgentConfigurationMetadata =>
  typeof value === "object" &&
  value !== null &&
  "blueprint_id" in value &&
  typeof (value as AgentConfigurationMetadata).blueprint_id === "string";

type AgentConfigurationTabProps = {
  data: Trace | Span;
  projectId: string;
};

const AgentConfigurationTab: React.FC<AgentConfigurationTabProps> = ({
  data,
  projectId,
}) => {
  const agentConfigMeta = (data.metadata as Record<string, unknown>)?.[
    AGENT_CONFIGURATION_METADATA_KEY
  ];
  const configMeta = isAgentConfigurationMetadata(agentConfigMeta)
    ? agentConfigMeta
    : undefined;
  const blueprintId = configMeta?.blueprint_id;

  const values = useMemo<BlueprintValue[]>(() => {
    if (!configMeta?.values) return [];
    return Object.entries(configMeta.values)
      .map(([key, { type, value, description }]) => ({
        key,
        type,
        value:
          typeof value === "string"
            ? value
            : typeof value === "object"
              ? JSON.stringify(value)
              : String(value),
        ...(description ? { description } : {}),
      }))
      .sort((a, b) => a.key.localeCompare(b.key));
  }, [configMeta?.values]);

  const versionMap = useConfigVersionMap(projectId);
  const version = blueprintId ? versionMap[blueprintId] : undefined;
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  if (!values.length) {
    return (
      <p className="comet-body-s py-8 text-center text-muted-slate">
        No configuration values available
      </p>
    );
  }

  return (
    <div className="flex flex-col gap-4">
      <div className="flex justify-between gap-2 px-1.5">
        <div className="flex items-center gap-2">
          <span className="comet-body-s-accented">Agent configuration</span>
          <TooltipWrapper content="Shows only the configuration used in this trace">
            <Info className="size-3.5 text-muted-slate" />
          </TooltipWrapper>
          {version !== undefined && (
            <Tag className="flex items-center gap-1" variant="gray" size="md">
              <GitCommitVertical className="size-3.5 shrink-0" />v{version}
            </Tag>
          )}
        </div>
        {blueprintId && (
          <Link
            to="/$workspaceName/projects/$projectId/traces"
            params={{ workspaceName, projectId }}
            search={{ tab: "configuration", configId: blueprintId }}
          >
            <Button variant="outline" size="2xs">
              <FileSliders className="mr-1 size-3 shrink-0 text-[var(--color-fuchsia)]" />
              Go to details
            </Button>
          </Link>
        )}
      </div>
      <Separator />
      <BlueprintValuesList values={values} />
    </div>
  );
};

export default AgentConfigurationTab;
