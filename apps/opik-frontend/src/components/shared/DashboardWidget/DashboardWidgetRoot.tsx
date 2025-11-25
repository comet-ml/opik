import React from "react";
import { Card } from "@/components/ui/card";
import { cn } from "@/lib/utils";

type DashboardWidgetRootProps = {
  children: React.ReactNode;
  className?: string;
};

const DashboardWidgetRoot: React.FC<DashboardWidgetRootProps> = ({
  children,
  className,
}) => {
  return (
    <div className="group h-full">
      <Card
        className={cn("flex h-full flex-col gap-2 rounded-md p-2", className)}
      >
        {children}
      </Card>
    </div>
  );
};

export default DashboardWidgetRoot;
