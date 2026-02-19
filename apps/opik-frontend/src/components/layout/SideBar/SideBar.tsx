import React, { useState } from "react";
import { Link } from "@tanstack/react-router";
import { Bolt, ChevronLeft, ChevronRight } from "lucide-react";
import useAppStore from "@/store/AppStore";
import { OnChangeFn } from "@/types/shared";
import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";
import { cn } from "@/lib/utils";
import Logo from "@/components/layout/Logo/Logo";
import usePluginsStore from "@/store/PluginsStore";
import ProvideFeedbackDialog from "@/components/layout/SideBar/FeedbackDialog/ProvideFeedbackDialog";
import { useOpenQuickStartDialog } from "@/components/pages-shared/onboarding/QuickstartDialog/QuickstartDialog";
import GitHubStarListItem from "@/components/layout/SideBar/GitHubStarListItem/GitHubStarListItem";
import SupportHubDropdown from "@/components/layout/SideBar/SupportHubDropdown/SupportHubDropdown";
import SidebarMenuItem, {
  MENU_ITEM_TYPE,
  MenuItem,
} from "@/components/layout/SideBar/MenuItem/SidebarMenuItem";
import SideBarMenuItems from "./SideBarMenuItems";

const HOME_PATH = "/$workspaceName/home";

const CONFIGURATION_ITEM: MenuItem = {
  id: "configuration",
  path: "/$workspaceName/configuration",
  type: MENU_ITEM_TYPE.router,
  icon: Bolt,
  label: "Configuration",
};

type SideBarProps = {
  expanded: boolean;
  setExpanded: OnChangeFn<boolean | undefined>;
};

const SideBar: React.FunctionComponent<SideBarProps> = ({
  expanded,
  setExpanded,
}) => {
  const [openProvideFeedback, setOpenProvideFeedback] = useState(false);
  const { open: openQuickstart } = useOpenQuickStartDialog();

  const { activeWorkspaceName: workspaceName } = useAppStore();
  const LogoComponent = usePluginsStore((state) => state.Logo);
  const SidebarInviteDevButton = usePluginsStore(
    (state) => state.SidebarInviteDevButton,
  );
  const SideBarMenuItemsComponent = usePluginsStore(
    (state) => state.SideBarMenuItems,
  );

  const logo = LogoComponent ? (
    <LogoComponent expanded={expanded} />
  ) : (
    <Logo expanded={expanded} />
  );

  const sideBarMenuItems = SideBarMenuItemsComponent ? (
    <SideBarMenuItemsComponent expanded={expanded} />
  ) : (
    <SideBarMenuItems expanded={expanded} canViewExperiments />
  );

  const renderBottomItems = () => {
    const bottomItems = [
      <SidebarMenuItem
        key="configuration"
        item={CONFIGURATION_ITEM}
        expanded={expanded}
        compact
      />,
      <SupportHubDropdown
        key="support-hub"
        expanded={expanded}
        openQuickstart={openQuickstart}
        openProvideFeedback={() => setOpenProvideFeedback(true)}
      />,
    ];

    if (SidebarInviteDevButton) {
      bottomItems.push(
        <SidebarInviteDevButton key="inviteDevButton" expanded={expanded} />,
      );
    }

    return bottomItems;
  };

  const renderExpandCollapseButton = () => {
    return (
      <Button
        variant="outline"
        size="icon-2xs"
        onClick={() => setExpanded((s) => !s)}
        className={cn(
          "absolute -right-3 top-2 hidden rounded-full z-50 lg:group-hover:flex",
        )}
      >
        {expanded ? <ChevronLeft /> : <ChevronRight />}
      </Button>
    );
  };

  return (
    <>
      <aside className="comet-sidebar-width group h-[calc(100vh-var(--banner-height))] border-r transition-all">
        <div className="comet-header-height relative flex w-full items-center justify-between gap-6 border-b">
          <Link
            to={HOME_PATH}
            className="absolute left-[18px] z-10 block"
            params={{ workspaceName }}
          >
            {logo}
          </Link>
        </div>
        <div className="relative flex h-[calc(100%-var(--header-height))]">
          {renderExpandCollapseButton()}
          <div className="flex min-h-0 grow flex-col justify-between overflow-auto px-3 py-4">
            <ul className="flex flex-col gap-1 pb-2">{sideBarMenuItems}</ul>
            <div className="flex flex-col gap-3">
              <Separator />
              <ul className="flex flex-col">
                <GitHubStarListItem expanded={expanded} />
                {renderBottomItems()}
              </ul>
            </div>
          </div>
        </div>
      </aside>

      <ProvideFeedbackDialog
        open={openProvideFeedback}
        setOpen={setOpenProvideFeedback}
      />
    </>
  );
};

export default SideBar;
