import React, { useMemo } from "react";
import { FileSliders, Info } from "lucide-react";
import { Link } from "@tanstack/react-router";

import { Span, Trace } from "@/types/traces";
import { BlueprintValue, BlueprintValueType } from "@/types/agent-configs";
import BlueprintValuesList from "@/v1/pages-shared/traces/ConfigurationTab/BlueprintValuesList";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import ConfigurationVersionTag from "@/shared/ConfigurationVersionTag/ConfigurationVersionTag";
import { Button } from "@/ui/button";
import { Separator } from "@/ui/separator";
import useAppStore from "@/store/AppStore";
import { AGENT_CONFIGURATION_METADATA_KEY } from "@/utils/agent-configurations";

type AgentConfigurationMetadata = {
  _blueprint_id: string;
  _mask_id?: string;
  values?: Record<
    string,
    { type: BlueprintValueType; value: unknown; description?: string }
  >;
  blueprint_version: string;
};

export const isAgentConfigurationMetadata = (
  value: unknown,
): value is AgentConfigurationMetadata =>
  typeof value === "object" &&
  value !== null &&
  "_blueprint_id" in value &&
  typeof (value as AgentConfigurationMetadata)._blueprint_id === "string";

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
  const blueprintId = configMeta?._blueprint_id;
  const maskId = configMeta?._mask_id;

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

  const version = configMeta?.blueprint_version;
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
          <TooltipWrapper content="Shows only used configuration values">
            <Info className="size-3.5 text-muted-slate" />
          </TooltipWrapper>
          {version !== undefined && (
            <ConfigurationVersionTag version={version} maskId={maskId} />
          )}
        </div>
        {blueprintId && !maskId && (
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
      <div className="px-1.5">
        <BlueprintValuesList values={values} />
      </div>
    </div>
  );
};

export default AgentConfigurationTab;
