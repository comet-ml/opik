import React from "react";
import useAppStore from "@/store/AppStore";
import usePluginsStore from "@/store/PluginsStore";
import { Link, Outlet } from "@tanstack/react-router";
import Logo from "@/components/layout/Logo/Logo";

const EmptyLayout = () => {
  const UserMenu = usePluginsStore((state) => state.UserMenu);
  const LogoComponent = usePluginsStore((state) => state.Logo);
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const logo = LogoComponent ? (
    <LogoComponent expanded={false} />
  ) : (
    <Logo expanded={false} />
  );

  return (
    <section className="relative flex h-screen min-h-0 w-screen min-w-0 flex-col overflow-hidden">
      <main>
        <nav className="comet-header-height flex w-full items-center justify-between gap-6 border-b pl-4 pr-6">
          <div className="flex-1">
            <Link to="/$workspaceName/projects" params={{ workspaceName }}>
              {logo}
            </Link>
          </div>

          {UserMenu ? <UserMenu /> : null}
        </nav>

        <section className="comet-header-inset absolute inset-x-0 bottom-0 overflow-auto bg-[#FCFCFD] px-6">
          <Outlet />
        </section>
      </main>
    </section>
  );
};

export default EmptyLayout;
