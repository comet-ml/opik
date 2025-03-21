import React from "react";
import { Outlet } from "@tanstack/react-router";
import SideBar from "@/components/layout/SideBar/SideBar";
import TopBar from "@/components/layout/TopBar/TopBar";
import { cn } from "@/lib/utils";
import useLocalStorageState from "use-local-storage-state";

const PageLayout = () => {
  const [expanded = true, setExpanded] =
    useLocalStorageState<boolean>("sidebar-expanded");

  return (
    <section
      className={cn(
        "relative flex h-screen min-h-0 w-screen min-w-0 flex-col",
        {
          "comet-expanded": expanded,
        },
      )}
    >
      <SideBar expanded={expanded} setExpanded={setExpanded} />
      <main className="comet-content-inset absolute inset-y-0 right-0 flex transition-all">
        <TopBar />
        <section className="comet-header-inset absolute inset-x-0 bottom-0 overflow-auto bg-soft-background px-6">
          <Outlet />
        </section>
      </main>
    </section>
  );
};

export default PageLayout;
