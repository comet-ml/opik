import React, { useCallback, useEffect, useState } from "react";
import { Outlet } from "@tanstack/react-router";
import SideBar from "@/components/layout/SideBar/SideBar";
import TopBar from "@/components/layout/TopBar/TopBar";
import { cn } from "@/lib/utils";
import useLocalStorageState from "use-local-storage-state";
import usePluginsStore from "@/store/PluginsStore";
import WelcomeWizardDialog from "@/components/pages-shared/WelcomeWizard/WelcomeWizardDialog";
import useWelcomeWizardStatus from "@/api/welcome-wizard/useWelcomeWizardStatus";
import { useIsFeatureEnabled } from "@/components/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import QuickstartDialog from "@/components/pages-shared/onboarding/QuickstartDialog/QuickstartDialog";

const MOBILE_BREAKPOINT = 1024; // lg breakpoint in Tailwind

const PageLayout = () => {
  const [storedExpanded = true, setStoredExpanded] =
    useLocalStorageState<boolean>("sidebar-expanded");
  const [bannerHeight, setBannerHeight] = useState(0);
  const [showWelcomeWizard, setShowWelcomeWizard] = useState(false);

  const welcomeWizardEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.WELCOME_WIZARD_ENABLED,
  );

  const { data: wizardStatus } = useWelcomeWizardStatus({
    enabled: welcomeWizardEnabled,
  });

  const RetentionBanner = usePluginsStore((state) => state.RetentionBanner);

  // Force sidebar collapsed on mobile, use stored preference on desktop
  const isMobile =
    typeof window !== "undefined" && window.innerWidth < MOBILE_BREAKPOINT;
  const expanded = isMobile ? false : storedExpanded;

  // Show welcome wizard if enabled and not completed
  useEffect(() => {
    if (
      welcomeWizardEnabled &&
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
  }, [welcomeWizardEnabled, wizardStatus, showWelcomeWizard]);

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

      <SideBar expanded={expanded} setExpanded={setStoredExpanded} />
      <main className="comet-content-inset absolute bottom-0 right-0 top-[var(--banner-height)] flex transition-all">
        <TopBar />
        <section className="comet-header-inset absolute inset-x-0 bottom-0 overflow-auto bg-soft-background px-6">
          <Outlet />
        </section>
      </main>

      {/* Welcome Wizard Dialog */}
      <WelcomeWizardDialog
        open={showWelcomeWizard}
        onClose={handleCloseWelcomeWizard}
      />

      {/* Quickstart Dialog */}
      <QuickstartDialog />
    </section>
  );
};

export default PageLayout;
