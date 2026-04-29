import Breadcrumbs from "@/v1/layout/Breadcrumbs/Breadcrumbs";
import usePluginsStore from "@/store/PluginsStore";
import AppDebugInfo from "@/v1/layout/AppDebugInfo/AppDebugInfo";
import SettingsMenu from "../SettingsMenu/SettingsMenu";

const TopBar = () => {
  const UserMenu = usePluginsStore((state) => state.UserMenu);
  const UpgradeButton = usePluginsStore((state) => state.UpgradeButton);

  return (
    <nav className="comet-header-height flex w-full items-center justify-between gap-6 border-b pl-4 pr-6">
      <div className="min-w-1 flex-1">
        <Breadcrumbs />
      </div>

      <AppDebugInfo />
      {UpgradeButton && (
        <div className="-mr-4">
          <UpgradeButton />
        </div>
      )}
      {UserMenu ? <UserMenu /> : <SettingsMenu />}
    </nav>
  );
};

export default TopBar;
