import React from "react";
import { Separator } from "@/ui/separator";
import Logo from "@/shared/Logo/Logo";

interface AgentOnboardingCardProps {
  title: string;
  description?: string;
  headerContent?: React.ReactNode;
  children: React.ReactNode;
  footer: React.ReactNode;
  showFooterSeparator?: boolean;
}

const AgentOnboardingCard: React.FC<AgentOnboardingCardProps> = ({
  title,
  description,
  headerContent,
  children,
  footer,
  showFooterSeparator = false,
}) => {
  return (
    <div className="fixed inset-0 z-50 flex items-start justify-center bg-soft-background pt-24">
      <div className="absolute left-[18px] top-[14.5px]">
        <Logo expanded />
      </div>
      <div className="flex h-fit max-h-[calc(100vh-8rem)] w-[40.5rem] flex-col gap-5 rounded-md border bg-background p-5 shadow-lg">
        {title && (
          <div className="flex flex-col gap-1.5">
            <h2 className="comet-title-s">{title}</h2>
            {description && (
              <p className="comet-body-s text-muted-slate">{description}</p>
            )}
          </div>
        )}
        {headerContent}
        <div className="overflow-y-auto">{children}</div>
        {footer && (
          <div className="-mt-2 flex flex-col gap-3">
            {showFooterSeparator && <Separator />}
            <div className="flex items-center justify-end gap-2">{footer}</div>
          </div>
        )}
      </div>
    </div>
  );
};

export default AgentOnboardingCard;
