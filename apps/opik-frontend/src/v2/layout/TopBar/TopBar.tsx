import React from "react";
import Breadcrumbs from "@/v2/layout/Breadcrumbs/Breadcrumbs";
import usePluginsStore from "@/store/PluginsStore";
import AppDebugInfo from "@/v2/layout/AppDebugInfo/AppDebugInfo";
import SettingsMenu from "../SettingsMenu/SettingsMenu";
import { Button } from "@/ui/button";
import OllieOwl from "@/icons/ollie-owl.svg?react";

type TopBarProps = {
  showOllieToggle?: boolean;
  onOpenAssistant?: () => void;
};

const TopBar: React.FC<TopBarProps> = ({
  showOllieToggle,
  onOpenAssistant,
}) => {
  const UserMenu = usePluginsStore((state) => state.UserMenu);
  const UpgradeButton = usePluginsStore((state) => state.UpgradeButton);

  return (
    <nav className="comet-header-height flex w-full items-center justify-between gap-6 border-b pl-4 pr-6">
      <div className="min-w-0 flex-1">
        <Breadcrumbs />
      </div>

      <div className="flex items-center gap-2">
        <AppDebugInfo />
        {UpgradeButton && <UpgradeButton />}
        {UserMenu ? <UserMenu /> : <SettingsMenu />}
        {showOllieToggle && (
          <Button
            size="icon-sm"
            variant="outline"
            className="text-[var(--color-ollie)]"
            onClick={onOpenAssistant}
          >
            <OllieOwl />
          </Button>
        )}
      </div>
    </nav>
  );
};

export default TopBar;
