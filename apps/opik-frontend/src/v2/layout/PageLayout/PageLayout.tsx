import React, { useCallback, useEffect, useRef, useState } from "react";
import { Outlet } from "@tanstack/react-router";
import SideBar from "@/v2/layout/SideBar/SideBar";
import TopBar from "@/v2/layout/TopBar/TopBar";
import useLocalStorageState from "use-local-storage-state";
import usePluginsStore from "@/store/PluginsStore";
import WelcomeWizardDialog from "@/v2/pages-shared/WelcomeWizard/WelcomeWizardDialog";
import useWelcomeWizardStatus from "@/api/welcome-wizard/useWelcomeWizardStatus";
import { useIsFeatureEnabled } from "@/contexts/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import LayoutDialogs from "@/v2/layout/LayoutDialogs";
import { PortalContainerProvider } from "@/lib/portal-container";
import SilentErrorBoundary from "@/shared/SilentErrorBoundary/SilentErrorBoundary";
import { useMediaQuery } from "@/hooks/useMediaQuery";

const PageLayout = () => {
  const [hostContainer, setHostContainer] = useState<HTMLDivElement | null>(
    null,
  );
  const [storedExpanded = true, setStoredExpanded] =
    useLocalStorageState<boolean>("sidebar-expanded");
  const [bannerHeight, setBannerHeight] = useState(0);
  const [showWelcomeWizard, setShowWelcomeWizard] = useState(false);
  const [assistantSidebarWidth, setAssistantSidebarWidth] = useState(0);
  const sidebarWrapperRef = useRef<HTMLDivElement>(null);

  const welcomeWizardEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.WELCOME_WIZARD_ENABLED,
  );

  const { data: wizardStatus } = useWelcomeWizardStatus({
    enabled: welcomeWizardEnabled,
  });

  const RetentionBanner = usePluginsStore((state) => state.RetentionBanner);
  const AssistantSidebar = usePluginsStore((state) => state.AssistantSidebar);

  const showAssistantSidebar = !!AssistantSidebar;

  const assistantWidth = showAssistantSidebar ? assistantSidebarWidth : 0;
  const isMobile = useMediaQuery(`(max-width: ${1023 + assistantWidth}px)`);
  const expanded = isMobile ? false : storedExpanded;

  const handleSidebarWidthChange = useCallback((width: number) => {
    if (sidebarWrapperRef.current) {
      sidebarWrapperRef.current.style.width = `${width}px`;
    }
    setAssistantSidebarWidth(width);
  }, []);

  useEffect(() => {
    if (
      welcomeWizardEnabled &&
      wizardStatus &&
      !wizardStatus.completed &&
      !showWelcomeWizard
    ) {
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
      className="relative flex h-screen min-h-0 w-screen min-w-0 flex-col"
      style={
        {
          "--banner-height": `${bannerHeight}px`,
          "--sidebar-width": expanded ? "240px" : "54px",
          "--assistant-sidebar-width": `${
            showAssistantSidebar ? assistantSidebarWidth : 0
          }px`,
        } as React.CSSProperties
      }
    >
      <div className="flex min-h-0 flex-1">
        <PortalContainerProvider value={hostContainer}>
          <div
            ref={setHostContainer}
            className="relative min-w-0 flex-1 overflow-hidden [transform:translateZ(0)]"
          >
            {RetentionBanner ? (
              <RetentionBanner onChangeHeight={setBannerHeight} />
            ) : null}

            <SideBar
              expanded={expanded}
              setExpanded={setStoredExpanded}
              locked={isMobile}
            />
            <main className="comet-content-inset absolute bottom-0 right-0 top-[var(--banner-height)] flex transition-all">
              <TopBar />
              <section className="comet-header-inset absolute inset-x-0 bottom-0 overflow-auto bg-soft-background px-6">
                <Outlet />
              </section>
            </main>

            <WelcomeWizardDialog
              open={showWelcomeWizard}
              onClose={handleCloseWelcomeWizard}
            />

            <LayoutDialogs />
          </div>
        </PortalContainerProvider>

        {showAssistantSidebar ? (
          <div ref={sidebarWrapperRef} className="relative z-[1] w-0 shrink-0">
            <SilentErrorBoundary>
              <AssistantSidebar onWidthChange={handleSidebarWidthChange} />
            </SilentErrorBoundary>
          </div>
        ) : null}
      </div>
    </section>
  );
};

export default PageLayout;
