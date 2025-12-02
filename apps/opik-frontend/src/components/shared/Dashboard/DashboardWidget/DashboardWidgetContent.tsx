import React from "react";
import { Separator } from "@/components/ui/separator";
import { CardContent } from "@/components/ui/card";
import { cn } from "@/lib/utils";

type DashboardWidgetContentProps = {
  children: React.ReactNode;
  showSeparator?: boolean;
  className?: string;
};

const DashboardWidgetContent: React.FunctionComponent<
  DashboardWidgetContentProps
> = ({ children, showSeparator = true, className }) => {
  return (
    <>
      {showSeparator && <Separator className="w-full" />}
      <CardContent className={cn("flex-1 overflow-hidden p-0", className)}>
        {children}
      </CardContent>
    </>
  );
};

export default DashboardWidgetContent;
