import React, { useCallback, useEffect, useState } from "react";
import { Outlet, useMatchRoute } from "@tanstack/react-router";
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
import { useIsPhone } from "@/hooks/useIsPhone";
import {
  ASSISTANT_SIDEBAR_COLLAPSED_WIDTH,
  getStoredAssistantSidebarWidth,
  isAssistantSidebarOpen,
  setAssistantSidebarOpen,
} from "@/constants/assistantSidebar";
import DemoProjectBanner from "@/v2/layout/DemoProjectBanner/DemoProjectBanner";

const PageLayout = () => {
  const [hostContainer, setHostContainer] = useState<HTMLDivElement | null>(
    null,
  );
  const [storedExpanded = true, setStoredExpanded] =
    useLocalStorageState<boolean>("sidebar-expanded");
  const [smallScreenExpanded, setSmallScreenExpanded] = useState(false);
  const [retentionBannerHeight, setRetentionBannerHeight] = useState(0);
  const [demoBannerHeight, setDemoBannerHeight] = useState(0);
  const bannerHeight = retentionBannerHeight + demoBannerHeight;
  const [showWelcomeWizard, setShowWelcomeWizard] = useState(false);
  const [assistantSidebarWidth, setAssistantSidebarWidth] = useState(() =>
    isAssistantSidebarOpen()
      ? getStoredAssistantSidebarWidth()
      : ASSISTANT_SIDEBAR_COLLAPSED_WIDTH,
  );

  const welcomeWizardEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.WELCOME_WIZARD_ENABLED,
  );

  const { data: wizardStatus } = useWelcomeWizardStatus({
    enabled: welcomeWizardEnabled,
  });

  const RetentionBanner = usePluginsStore((state) => state.RetentionBanner);
  const AssistantSidebar = usePluginsStore((state) => state.AssistantSidebar);

  const matchRoute = useMatchRoute();
  const isProjectHome = !!matchRoute({
    to: "/$workspaceName/projects/$projectId/home",
  });

  const showAssistantSidebar = !!AssistantSidebar && !isProjectHome;

  const assistantWidth = showAssistantSidebar ? assistantSidebarWidth : 0;
  const { isPhone } = useIsPhone();
  const isSmall = useMediaQuery(`(max-width: ${1023 + assistantWidth}px)`);

  const isAssistantOpen =
    assistantSidebarWidth > ASSISTANT_SIDEBAR_COLLAPSED_WIDTH;
  // On phones the assistant renders as a fixed overlay when open and is
  // fully unmounted when collapsed. The layout var stays 0px so
  // `.comet-content-inset`'s calc resolves correctly.
  const layoutAssistantWidth =
    showAssistantSidebar && !isPhone ? `${assistantSidebarWidth}px` : "0px";

  const handleOpenAssistant = useCallback(() => {
    setAssistantSidebarOpen(true);
    setAssistantSidebarWidth(getStoredAssistantSidebarWidth());
  }, []);

  const expanded = isPhone
    ? false
    : isSmall
      ? smallScreenExpanded
      : storedExpanded;

  const toggleExpanded = useCallback(() => {
    if (isSmall) {
      setSmallScreenExpanded((s) => !s);
    } else {
      setStoredExpanded((s) => !s);
    }
  }, [isSmall, setStoredExpanded]);

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
          "--sidebar-width": expanded ? "240px" : "48px",
          "--assistant-sidebar-width": layoutAssistantWidth,
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
              <RetentionBanner onChangeHeight={setRetentionBannerHeight} />
            ) : null}
            <DemoProjectBanner onChangeHeight={setDemoBannerHeight} />

            <SideBar
              expanded={expanded}
              canToggle={!isPhone}
              onToggle={toggleExpanded}
            />
            <main className="comet-content-inset absolute bottom-0 right-0 top-[var(--banner-height)] flex transition-all">
              <TopBar
                showOllieToggle={
                  isPhone && showAssistantSidebar && !isAssistantOpen
                }
                onOpenAssistant={handleOpenAssistant}
              />
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

        {showAssistantSidebar && (!isPhone || isAssistantOpen) ? (
          <div
            className={
              isPhone ? "fixed inset-0 z-40" : "relative z-[1] shrink-0"
            }
            style={
              isPhone ? undefined : { width: `${assistantSidebarWidth}px` }
            }
          >
            <SilentErrorBoundary>
              <AssistantSidebar onWidthChange={setAssistantSidebarWidth} />
            </SilentErrorBoundary>
          </div>
        ) : null}
      </div>
    </section>
  );
};

export default PageLayout;
