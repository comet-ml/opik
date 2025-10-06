import React, { useCallback, useEffect, useState } from "react";
import { Outlet } from "@tanstack/react-router";
import SideBar from "@/components/layout/SideBar/SideBar";
import TopBar from "@/components/layout/TopBar/TopBar";
import { cn } from "@/lib/utils";
import useLocalStorageState from "use-local-storage-state";
import usePluginsStore from "@/store/PluginsStore";
import OpenSourceWelcomeWizardDialog from "@/components/pages-shared/OpenSourceWelcomeWizard/OpenSourceWelcomeWizardDialog";
import useOpenSourceWelcomeWizardStatus from "@/api/open-source-welcome-wizard/useOpenSourceWelcomeWizardStatus";
import { useFeatureToggles } from "@/components/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";

const PageLayout = () => {
  const [expanded = true, setExpanded] =
    useLocalStorageState<boolean>("sidebar-expanded");
  const [bannerHeight, setBannerHeight] = useState(0);
  const [showWelcomeWizard, setShowWelcomeWizard] = useState(false);

  const toggles = useFeatureToggles();
  const openSourceWelcomeWizardEnabled =
    toggles?.[FeatureToggleKeys.OPEN_SOURCE_WELCOME_WIZARD_ENABLED] ?? false;

  const { data: wizardStatus } = useOpenSourceWelcomeWizardStatus({
    enabled: openSourceWelcomeWizardEnabled,
  });

  const RetentionBanner = usePluginsStore((state) => state.RetentionBanner);

  // Show OSS welcome wizard if enabled and not completed
  useEffect(() => {
    if (
      openSourceWelcomeWizardEnabled &&
      wizardStatus &&
      !wizardStatus.completed &&
      !showWelcomeWizard
    ) {
      // Show wizard after a small delay for better UX
      const timer = setTimeout(() => {
        setShowWelcomeWizard(true);
      }, 500);

      return () => clearTimeout(timer);
    }
  }, [
    openSourceWelcomeWizardEnabled,
    wizardStatus,
    showWelcomeWizard,
  ]);

  const handleCloseWelcomeWizard = useCallback(() => {
    setShowWelcomeWizard(false);
  }, []);

  return (
    <section
      className={cn(
        "relative flex h-screen min-h-0 w-screen min-w-0 flex-col",
        {
          "comet-expanded": expanded,
        },
      )}
      style={
        {
          "--banner-height": `${bannerHeight}px`,
        } as React.CSSProperties
      }
    >
      {RetentionBanner ? (
        <RetentionBanner onChangeHeight={setBannerHeight} />
      ) : null}

      <SideBar expanded={expanded} setExpanded={setExpanded} />
      <main className="comet-content-inset absolute bottom-0 right-0 top-[var(--banner-height)] flex transition-all">
        <TopBar />
        <section className="comet-header-inset absolute inset-x-0 bottom-0 overflow-auto bg-soft-background px-6">
          <Outlet />
        </section>
      </main>

            {/* Open Source Welcome Wizard Dialog */}
            <OpenSourceWelcomeWizardDialog
              open={showWelcomeWizard}
              onClose={handleCloseWelcomeWizard}
            />
    </section>
  );
};

export default PageLayout;
