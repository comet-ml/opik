import { ArrowUpRight } from "lucide-react";
import { Link } from "@tanstack/react-router";
import { DropdownMenuItem, DropdownMenuSeparator } from "@/ui/dropdown-menu";
import { cn } from "@/lib/utils";
import { useOpikWorkspaceName } from "@/store/AppStore";
import { useAiSpend } from "@/contexts/AiSpendContext";
import usePluginsStore from "@/store/PluginsStore";
import { useIsFeatureEnabled } from "@/contexts/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import { buildUrl } from "./utils";

type AppLinkItemProps = {
  badge: string;
  badgeClassName: string;
  label: string;
  to?: string;
  params?: { workspaceName: string };
  onClick?: () => void;
};

const AppLinkItem = ({
  badge,
  badgeClassName,
  label,
  to,
  params,
  onClick,
}: AppLinkItemProps) => {
  const content = (
    <>
      <span
        className={cn(
          "mr-2 flex size-5 shrink-0 items-center justify-center rounded text-[10px] font-medium text-white",
          badgeClassName,
        )}
      >
        {badge}
      </span>
      <span className="truncate">{label}</span>
      <ArrowUpRight className="ml-auto size-4 shrink-0 text-light-slate" />
    </>
  );

  return to ? (
    <DropdownMenuItem asChild className="cursor-pointer">
      <Link to={to} params={params}>
        {content}
      </Link>
    </DropdownMenuItem>
  ) : (
    <DropdownMenuItem className="cursor-pointer" onClick={onClick}>
      {content}
    </DropdownMenuItem>
  );
};

type UserMenuAppLinksProps = {
  isLLMOnlyOrganization: boolean;
};

const UserMenuAppLinks = ({ isLLMOnlyOrganization }: UserMenuAppLinksProps) => {
  const opikWorkspaceName = useOpikWorkspaceName();
  const {
    hasAccess: hasAiSpendAccess,
    isSpendWorkspaceActive,
    goToCostIntelligence,
  } = useAiSpend();
  const hasAiSpendPlugin = usePluginsStore((state) =>
    state.hasPlugin("ai-spend"),
  );
  const costIntelligenceFeatureEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.COST_INTELLIGENCE_ENABLED,
  );

  const showOpikReturn = Boolean(isSpendWorkspaceActive);
  const showCostIntelligence =
    hasAiSpendPlugin &&
    hasAiSpendAccess &&
    costIntelligenceFeatureEnabled &&
    !isSpendWorkspaceActive;
  const showExperimentManagement = !isLLMOnlyOrganization;

  if (!showOpikReturn && !showCostIntelligence && !showExperimentManagement) {
    return null;
  }

  const switchToEM = () =>
    (window.location.href = buildUrl(
      opikWorkspaceName,
      opikWorkspaceName,
      "&changeApplication=em",
    ));

  return (
    <>
      <DropdownMenuSeparator />
      {showOpikReturn && (
        <AppLinkItem
          badge="O"
          badgeClassName="bg-chart-green"
          label="Opik"
          to="/$workspaceName/home"
          params={{ workspaceName: opikWorkspaceName }}
        />
      )}
      {showCostIntelligence && (
        <AppLinkItem
          badge="CI"
          badgeClassName="bg-chart-green"
          label="Cost Intelligence"
          onClick={goToCostIntelligence}
        />
      )}
      {showExperimentManagement && (
        <AppLinkItem
          badge="EM"
          badgeClassName="bg-[var(--feature-experiment-management)]"
          label="Experiment Management"
          onClick={switchToEM}
        />
      )}
    </>
  );
};

export default UserMenuAppLinks;
