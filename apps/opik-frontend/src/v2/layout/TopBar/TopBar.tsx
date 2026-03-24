import Breadcrumbs from "@/v2/layout/Breadcrumbs/Breadcrumbs";
import usePluginsStore from "@/store/PluginsStore";
import AppDebugInfo from "@/v2/layout/AppDebugInfo/AppDebugInfo";
import SettingsMenu from "../SettingsMenu/SettingsMenu";
import SupportHub from "@/v2/layout/SupportHub/SupportHub";

const TopBar = () => {
  const UserMenu = usePluginsStore((state) => state.UserMenu);

  return (
    <nav className="comet-header-height flex w-full items-center justify-between gap-6 border-b pl-4 pr-6">
      <div className="min-w-1 flex-1">
        <Breadcrumbs />
      </div>

      <div className="flex items-center gap-2">
        <AppDebugInfo />
        <SupportHub />
        {UserMenu ? <UserMenu /> : <SettingsMenu />}
      </div>
    </nav>
  );
};

export default TopBar;
