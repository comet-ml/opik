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
} from "@/constants/assistantSidebar";

const PageLayout = () => {
  const [hostContainer, setHostContainer] = useState<HTMLDivElement | null>(
    null,
  );
  const [storedExpanded = true, setStoredExpanded] =
    useLocalStorageState<boolean>("sidebar-expanded");
  const [smallScreenExpanded, setSmallScreenExpanded] = useState(false);
  const [bannerHeight, setBannerHeight] = useState(0);
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
  // On phones when the assistant is open, render it as a fixed overlay so it
  // doesn't consume flex-row width and collapse main content. The layout var
  // stays 0px so `.comet-content-inset`'s calc resolves correctly.
  const isPhoneAssistantOverlay = isPhone && isAssistantOpen;
  const layoutAssistantWidth =
    showAssistantSidebar && !isPhoneAssistantOverlay
      ? `${assistantSidebarWidth}px`
      : "0px";

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
          "--sidebar-width": expanded ? "240px" : "54px",
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
              <RetentionBanner onChangeHeight={setBannerHeight} />
            ) : null}

            <SideBar
              expanded={expanded}
              canToggle={!isPhone}
              onToggle={toggleExpanded}
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
          <div
            className={
              isPhoneAssistantOverlay
                ? "fixed inset-0 z-40"
                : "relative z-[1] shrink-0"
            }
            style={
              isPhoneAssistantOverlay
                ? undefined
                : { width: `${assistantSidebarWidth}px` }
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
