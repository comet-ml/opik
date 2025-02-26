import React, { ReactNode, useState } from "react";
import { cn } from "@/lib/utils";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { ChevronDown, ChevronUp } from "lucide-react";

type CompareChartContainerProps = {
  title: string;
  children: ReactNode;
  className?: string;
};

const CompareChartContainer: React.FC<CompareChartContainerProps> = ({
  title,
  children,
  className,
}) => {
  const [isCollapsed, setIsCollapsed] = useState(false);

  return (
    <Card className={cn("min-w-[400px]", className)}>
      <CardHeader className="space-y-0.5 px-4 pt-3">
        <div className="flex items-center justify-between">
          <CardTitle className="comet-body-s-accented">{title}</CardTitle>
          <button
            onClick={() => setIsCollapsed(!isCollapsed)}
            className="text-muted-foreground hover:text-foreground"
          >
            {isCollapsed ? (
              <ChevronDown className="size-4" />
            ) : (
              <ChevronUp className="size-4" />
            )}
          </button>
        </div>
        <CardDescription className="comet-body-xs text-xs"></CardDescription>
      </CardHeader>
      {!isCollapsed && (
        <CardContent className="px-4 pb-3">
          <div className="h-64 w-full">{children}</div>
        </CardContent>
      )}
    </Card>
  );
};

export default CompareChartContainer;
