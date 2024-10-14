import React from "react";
import Breadcrumbs from "@/components/layout/Breadcrumbs/Breadcrumbs";
import usePluginsStore from "@/store/PluginsStore";

const TopBar = () => {
  const UserMenu = usePluginsStore((state) => state.UserMenu);

  return (
    <nav className="comet-header-height flex w-full items-center justify-between gap-6 border-b pl-4 pr-6">
      <div className="min-w-1 flex-1">
        <Breadcrumbs />
      </div>

      {UserMenu ? <UserMenu /> : null}
    </nav>
  );
};

export default TopBar;
