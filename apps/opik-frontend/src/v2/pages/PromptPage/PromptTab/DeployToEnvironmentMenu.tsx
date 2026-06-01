import React, { useCallback, useMemo } from "react";
import {
  ChevronDown,
  Check,
  CircleFadingArrowUp,
  Settings2,
  X,
} from "lucide-react";
import { Link } from "@tanstack/react-router";

import { Button } from "@/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
import { EnvironmentSquare } from "@/shared/EnvironmentLabel/EnvironmentLabel";
import useEnvironmentsList from "@/api/environments/useEnvironmentsList";
import useSetPromptVersionEnvironmentMutation from "@/api/prompts/useSetPromptVersionEnvironmentMutation";
import useAppStore from "@/store/AppStore";
import { useToast } from "@/ui/use-toast";
import { usePermissions } from "@/contexts/PermissionsContext";
import { CONFIGURATION_TABS } from "@/v2/constants/configuration";
import { PromptVersion } from "@/types/prompts";

type DeployToEnvironmentMenuProps = {
  promptId: string;
  versionId: string;
  versionLabel: string;
  versions: PromptVersion[] | undefined;
  totalVersions: number;
  activeEnvironments: string[];
};

const DeployToEnvironmentMenu: React.FC<DeployToEnvironmentMenuProps> = ({
  promptId,
  versionId,
  versionLabel,
  versions,
  totalVersions,
  activeEnvironments,
}) => {
  const { toast } = useToast();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const {
    permissions: { canConfigureWorkspaceSettings },
  } = usePermissions();
  const { data: environmentsData } = useEnvironmentsList({
    enabled: canConfigureWorkspaceSettings,
  });
  const { mutate: setVersionEnvironment, isPending: isDeploying } =
    useSetPromptVersionEnvironmentMutation();

  const environments = useMemo(
    () => environmentsData?.content ?? [],
    [environmentsData?.content],
  );

  const activeEnvSet = useMemo(
    () => new Set(activeEnvironments),
    [activeEnvironments],
  );

  const environmentOwners = useMemo(() => {
    const map = new Map<string, { version: PromptVersion; index: number }>();
    // `versions` is newest-first; only keep the first writer per environment so
    // the "Currently vN" label reflects the newest version assigned to that env,
    // not whichever historical version was iterated last.
    versions?.forEach((v, index) => {
      v.environments?.forEach((env) => {
        if (!map.has(env)) map.set(env, { version: v, index });
      });
    });
    return map;
  }, [versions]);

  const applyEnvironments = useCallback(
    (next: string[], description: string) => {
      setVersionEnvironment(
        { promptId, versionId, environments: next },
        { onSuccess: () => toast({ description }) },
      );
    },
    [promptId, versionId, setVersionEnvironment, toast],
  );

  const handleToggle = useCallback(
    (envName: string) => {
      if (!canConfigureWorkspaceSettings) return;
      if (activeEnvSet.has(envName)) {
        const next = activeEnvironments.filter((e) => e !== envName);
        applyEnvironments(next, `Removed ${versionLabel} from ${envName}`);
      } else {
        const next = [...activeEnvironments, envName];
        applyEnvironments(next, `Deployed ${versionLabel} to ${envName}`);
      }
    },
    [
      canConfigureWorkspaceSettings,
      activeEnvSet,
      activeEnvironments,
      versionLabel,
      applyEnvironments,
    ],
  );

  const handleClearAll = useCallback(() => {
    if (!canConfigureWorkspaceSettings) return;
    if (activeEnvironments.length === 0) return;
    applyEnvironments([], `Removed ${versionLabel} from all environments`);
  }, [
    canConfigureWorkspaceSettings,
    activeEnvironments.length,
    versionLabel,
    applyEnvironments,
  ]);

  if (!canConfigureWorkspaceSettings) return null;

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button
          variant="ghost"
          size="sm"
          className="px-0"
          disabled={!versionId || isDeploying}
        >
          <CircleFadingArrowUp className="mr-1.5 size-3.5" />
          Deploy to
          <ChevronDown className="ml-1 size-3.5" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="min-w-[220px]">
        {environments.length === 0 ? (
          <DropdownMenuItem size="sm" disabled>
            No environments configured
          </DropdownMenuItem>
        ) : (
          environments.map((env) => {
            const owner = environmentOwners.get(env.name);
            const isActiveHere = activeEnvSet.has(env.name);
            const ownerLabel =
              !isActiveHere && owner && totalVersions > 0
                ? `Currently v${totalVersions - owner.index}`
                : "";
            return (
              <DropdownMenuItem
                key={env.id}
                size="sm"
                onSelect={(e) => {
                  e.preventDefault();
                  handleToggle(env.name);
                }}
              >
                <div className="flex min-w-0 flex-1 items-center gap-2">
                  <EnvironmentSquare name={env.name} color={env.color} />
                  <span className="truncate">{env.name}</span>
                </div>
                <span className="comet-body-xs ml-3 shrink-0 text-light-slate">
                  {isActiveHere ? (
                    <Check className="size-3.5" />
                  ) : ownerLabel ? (
                    ownerLabel
                  ) : null}
                </span>
              </DropdownMenuItem>
            );
          })
        )}
        {activeEnvironments.length > 0 && (
          <>
            <DropdownMenuSeparator />
            <DropdownMenuItem size="sm" onSelect={handleClearAll}>
              <X className="mr-2 size-3.5 shrink-0 text-muted-slate" />
              Remove from all
            </DropdownMenuItem>
          </>
        )}
        <DropdownMenuSeparator />
        <DropdownMenuItem size="sm" asChild>
          <Link
            to="/$workspaceName/configuration"
            params={{ workspaceName }}
            search={{ tab: CONFIGURATION_TABS.ENVIRONMENTS }}
          >
            <Settings2 className="mr-2 size-3.5 shrink-0 text-muted-slate" />
            Manage environments
          </Link>
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
};

export default DeployToEnvironmentMenu;
