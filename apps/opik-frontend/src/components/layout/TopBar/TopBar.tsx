import Breadcrumbs from "@/components/layout/Breadcrumbs/Breadcrumbs";
import usePluginsStore from "@/store/PluginsStore";
import AppDebugInfo from "@/components/layout/AppDebugInfo/AppDebugInfo";
import SettingsMenu from "../SettingsMenu/SettingsMenu";

const TopBar = () => {
  const UserMenu = usePluginsStore((state) => state.UserMenu);

  return (
    <nav className="comet-header-height flex w-full items-center justify-between gap-6 border-b pl-4 pr-6">
      <div className="min-w-1 flex-1">
        <Breadcrumbs />
      </div>

      <AppDebugInfo />
      {UserMenu ? <UserMenu /> : <SettingsMenu />}
    </nav>
  );
};

export default TopBar;
