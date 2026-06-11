import React from "react";
import { useNavigate } from "@tanstack/react-router";
import { LayoutDashboard, Trophy, Undo2, Zap } from "lucide-react";
import { cn } from "@/lib/utils";
import { useActiveWorkspaceName } from "@/store/AppStore";
import { useAiSpend } from "@/contexts/AiSpendContext";
import { Separator } from "@/ui/separator";
import { Button } from "@/ui/button";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import SidebarMenuItem, {
  MENU_ITEM_TYPE,
  MenuItem,
} from "@/v2/layout/SideBar/MenuItem/SidebarMenuItem";
import GitHubStarListItem from "@/v2/layout/SideBar/GitHubStarListItem/GitHubStarListItem";

const OVERVIEW_ITEMS: MenuItem[] = [
  {
    id: "ai_spend_home",
    path: "/$workspaceName/ai-spend/home",
    type: MENU_ITEM_TYPE.router,
    icon: LayoutDashboard,
    label: "Home",
  },
  {
    id: "ai_spend_leaderboard",
    path: "/$workspaceName/ai-spend/leaderboard",
    type: MENU_ITEM_TYPE.router,
    icon: Trophy,
    label: "User leaderboard",
  },
];

const groupLabelClasses =
  "comet-body-xs-accented truncate px-2 py-1 text-light-slate";

interface AiSpendSidebarContentProps {
  expanded: boolean;
}

const AiSpendSidebarContent: React.FC<AiSpendSidebarContentProps> = ({
  expanded,
}) => {
  const workspaceName = useActiveWorkspaceName();
  const navigate = useNavigate();
  const { organizationName } = useAiSpend();

  const goBackToOpik = () =>
    navigate({ to: "/$workspaceName/home", params: { workspaceName } });

  const header = expanded ? (
    <div className="flex items-center gap-2 px-1 py-0.5">
      <span className="flex size-8 shrink-0 items-center justify-center rounded-md bg-muted text-light-slate">
        <Zap className="size-4" />
      </span>
      <div className="flex min-w-0 flex-col">
        <TooltipWrapper content={organizationName ?? ""}>
          <span className="comet-body-xs-accented truncate text-light-slate">
            {organizationName}
          </span>
        </TooltipWrapper>
        <span className="comet-body-s-accented truncate text-foreground">
          Cost Intelligence
        </span>
      </div>
    </div>
  ) : (
    <span className="comet-body-xs-accented flex size-7 shrink-0 items-center justify-center self-center rounded-md bg-muted text-muted-slate">
      <Zap className="size-4" />
    </span>
  );

  const backButton = expanded ? (
    <Button
      variant="outline"
      size="2xs"
      className="comet-body-xs-accented w-fit max-w-full gap-1 rounded px-1.5"
      onClick={goBackToOpik}
    >
      <Undo2 className="size-3 shrink-0" />
      <span className="truncate">Back to Opik</span>
    </Button>
  ) : (
    <TooltipWrapper content="Back to Opik" side="right" delayDuration={0}>
      <Button
        variant="outline"
        size="icon-2xs"
        className="rounded"
        onClick={goBackToOpik}
      >
        <Undo2 />
      </Button>
    </TooltipWrapper>
  );

  return (
    <>
      {header}
      <Separator className={cn("my-2", !expanded && "mx-1 w-auto")} />
      <div className="flex min-h-0 flex-1 flex-col overflow-auto">
        {expanded && <div className={groupLabelClasses}>Overview</div>}
        <ul className={cn("flex flex-col", !expanded && "gap-1")}>
          {OVERVIEW_ITEMS.map((item) => (
            <SidebarMenuItem key={item.id} item={item} expanded={expanded} />
          ))}
        </ul>
      </div>

      <div className="shrink-0 pt-2">
        {backButton}
        <Separator className={cn("my-2", !expanded && "mx-1 w-auto")} />
        <ul className={cn("flex flex-col", !expanded && "gap-1")}>
          <GitHubStarListItem expanded={expanded} />
        </ul>
      </div>
    </>
  );
};

export default AiSpendSidebarContent;
