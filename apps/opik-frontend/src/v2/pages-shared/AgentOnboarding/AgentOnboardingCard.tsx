import React from "react";
import { Separator } from "@/ui/separator";

interface AgentOnboardingCardProps {
  title: string;
  description?: string;
  children: React.ReactNode;
  footer: React.ReactNode;
}

const AgentOnboardingCard: React.FC<AgentOnboardingCardProps> = ({
  title,
  description,
  children,
  footer,
}) => {
  return (
    <div className="fixed inset-0 z-50 flex justify-center pt-40">
      <div className="h-fit w-full max-w-[648px] rounded-md border bg-background p-5 shadow-lg">
        <div className="flex flex-col gap-1.5 pb-2">
          <h2 className="comet-title-s">{title}</h2>
          {description && (
            <p className="comet-body-s text-muted-slate">{description}</p>
          )}
        </div>
        <div className="py-4">{children}</div>
        <Separator />
        <div className="flex justify-end pt-4">{footer}</div>
      </div>
    </div>
  );
};

export default AgentOnboardingCard;
