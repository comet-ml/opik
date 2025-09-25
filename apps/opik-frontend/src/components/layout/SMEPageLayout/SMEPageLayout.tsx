import React from "react";
import usePluginsStore from "@/store/PluginsStore";
import { Outlet } from "@tanstack/react-router";
import Logo from "@/components/layout/Logo/Logo";
import ThemeToggle from "@/components/layout/ThemeToggle/ThemeToggle";

export const SMEPageLayout = ({
  children = <Outlet />,
}: {
  children?: React.ReactNode;
}) => {
  const LogoComponent = usePluginsStore((state) => state.Logo);

  const logo = LogoComponent ? (
    <LogoComponent expanded={true} />
  ) : (
    <Logo expanded={false} />
  );

  return (
    <section className="relative flex h-screen min-h-0 w-screen min-w-0 flex-col overflow-hidden">
      <main>
        <nav className="comet-header-height flex w-full items-center justify-between gap-6 border-b pl-4 pr-6">
          <div className="flex-1 pl-0.5">{logo}</div>
          <ThemeToggle />
        </nav>

        <section className="comet-header-inset absolute inset-x-0 bottom-0 overflow-auto bg-soft-background px-6">
          {children}
        </section>
      </main>
    </section>
  );
};

export default SMEPageLayout;
