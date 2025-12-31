import React from "react";
import { useIsPhone } from "@/hooks/useIsPhone";
import { Button } from "@/components/ui/button";
import { ChevronDown } from "lucide-react";
import { useTheme } from "@/components/theme-provider";
import { THEME_MODE } from "@/constants/theme";

type IntegrationListLayoutProps = {
  leftSidebar: React.ReactNode;
  rightSidebar: React.ReactNode;
  children: React.ReactNode;
  isMenuExpanded?: boolean;
  onMenuToggle?: () => void;
  selectedIntegration?: {
    label: string;
    logo: string;
    logoWhite?: string;
  };
};
const IntegrationListLayout: React.FC<IntegrationListLayoutProps> = ({
  leftSidebar,
  rightSidebar,
  children,
  isMenuExpanded = true,
  onMenuToggle,
  selectedIntegration,
}) => {
  const { isPhonePortrait } = useIsPhone();
  const { themeMode } = useTheme();

  if (isPhonePortrait) {
    return (
      <div className="m-auto flex w-full flex-col gap-6 px-4">
        {/* Collapsed state: Show selected framework with change button */}
        {!isMenuExpanded && selectedIntegration && (
          <div className="flex flex-col gap-4">
            <div className="flex items-center justify-between rounded-md border bg-background p-4">
              <div className="flex items-center gap-3">
                <img
                  alt={selectedIntegration.label}
                  src={
                    themeMode === THEME_MODE.DARK &&
                    selectedIntegration.logoWhite
                      ? selectedIntegration.logoWhite
                      : selectedIntegration.logo
                  }
                  className="size-[32px] shrink-0"
                />
                <div className="comet-body-s font-medium">
                  {selectedIntegration.label}
                </div>
              </div>
              <Button
                variant="outline"
                size="sm"
                onClick={onMenuToggle}
                className="flex items-center gap-1"
              >
                Change
                <ChevronDown className="size-4" />
              </Button>
            </div>
          </div>
        )}

        {/* Expanded state: Show all frameworks */}
        {isMenuExpanded && (
          <div className="flex flex-col gap-4">{leftSidebar}</div>
        )}

        {/* Always show code and sidebar when framework is selected and menu is collapsed */}
        {!isMenuExpanded && (
          <>
            <div className="flex w-full flex-col gap-6">{children}</div>
            <div className="flex flex-col gap-6">{rightSidebar}</div>
          </>
        )}
      </div>
    );
  }

  return (
    <div className="m-auto flex w-full max-w-[1440px] gap-6">
      <div className="sticky top-0 flex w-[250px] shrink-0 flex-col gap-4 self-start">
        {leftSidebar}
      </div>
      <div className="flex w-full min-w-[450px] flex-1 grow flex-col gap-6">
        {children}
      </div>
      <div className="sticky top-0 flex w-[250px] shrink-0 flex-col gap-6 self-start">
        {rightSidebar}
      </div>
    </div>
  );
};

export default IntegrationListLayout;
