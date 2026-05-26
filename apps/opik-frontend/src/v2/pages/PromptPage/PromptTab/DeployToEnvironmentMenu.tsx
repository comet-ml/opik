import React, { useCallback, useMemo } from "react";
import {
  ChevronDown,
  Check,
  CircleFadingArrowUp,
  SlidersHorizontal,
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
  activeEnvironment: string | null;
};

const DeployToEnvironmentMenu: React.FC<DeployToEnvironmentMenuProps> = ({
  promptId,
  versionId,
  versionLabel,
  versions,
  totalVersions,
  activeEnvironment,
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
    () =>
      (environmentsData?.content ?? [])
        .slice()
        .sort((a, b) => a.position - b.position),
    [environmentsData?.content],
  );

  const environmentOwners = useMemo(() => {
    const map = new Map<string, { version: PromptVersion; index: number }>();
    // `versions` is newest-first; only keep the first writer per environment so
    // the "Currently vN" label reflects the newest version assigned to that env,
    // not whichever historical version was iterated last.
    versions?.forEach((v, index) => {
      if (v.environment && !map.has(v.environment)) {
        map.set(v.environment, { version: v, index });
      }
    });
    return map;
  }, [versions]);

  const handleDeploy = useCallback(
    (envName: string) => {
      if (!canConfigureWorkspaceSettings) return;
      if (activeEnvironment === envName) return;
      setVersionEnvironment(
        { promptId, versionId, environment: envName },
        {
          onSuccess: () =>
            toast({ description: `Deployed ${versionLabel} to ${envName}` }),
        },
      );
    },
    [
      promptId,
      versionId,
      versionLabel,
      activeEnvironment,
      canConfigureWorkspaceSettings,
      setVersionEnvironment,
      toast,
    ],
  );

  const handleClear = useCallback(() => {
    if (!canConfigureWorkspaceSettings) return;
    if (!activeEnvironment) return;
    setVersionEnvironment(
      { promptId, versionId, environment: null },
      {
        onSuccess: () =>
          toast({
            description: `Removed ${versionLabel} from ${activeEnvironment}`,
          }),
      },
    );
  }, [
    promptId,
    versionId,
    versionLabel,
    activeEnvironment,
    canConfigureWorkspaceSettings,
    setVersionEnvironment,
    toast,
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
          <DropdownMenuItem disabled>
            No environments configured
          </DropdownMenuItem>
        ) : (
          environments.map((env) => {
            const owner = environmentOwners.get(env.name);
            const ownerLabel =
              owner && totalVersions > 0
                ? `v${totalVersions - owner.index}`
                : "";
            const isActiveHere = activeEnvironment === env.name;
            return (
              <DropdownMenuItem
                key={env.id}
                selected={isActiveHere}
                disabled={isActiveHere}
                onSelect={() => handleDeploy(env.name)}
              >
                <div className="flex min-w-0 flex-1 items-center gap-2">
                  <EnvironmentSquare color={env.color} />
                  <span className="truncate">{env.name}</span>
                </div>
                <span className="comet-body-xs ml-3 shrink-0 text-light-slate">
                  {isActiveHere ? (
                    <Check className="size-3.5" />
                  ) : ownerLabel ? (
                    `Currently ${ownerLabel}`
                  ) : null}
                </span>
              </DropdownMenuItem>
            );
          })
        )}
        {activeEnvironment && (
          <>
            <DropdownMenuSeparator />
            <DropdownMenuItem onSelect={handleClear}>
              Remove from {activeEnvironment}
            </DropdownMenuItem>
          </>
        )}
        <DropdownMenuSeparator />
        <DropdownMenuItem asChild>
          <Link
            to="/$workspaceName/configuration"
            params={{ workspaceName }}
            search={{ tab: CONFIGURATION_TABS.ENVIRONMENTS }}
          >
            <SlidersHorizontal className="mr-2 size-3.5 shrink-0 text-muted-slate" />
            Manage environments
          </Link>
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
};

export default DeployToEnvironmentMenu;
