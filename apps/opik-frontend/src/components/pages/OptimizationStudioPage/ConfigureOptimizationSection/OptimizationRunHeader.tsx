import React from "react";
import { Link } from "@tanstack/react-router";
import { ExternalLink } from "lucide-react";
import { Button } from "@/components/ui/button";
import useAppStore from "@/store/AppStore";
import { Optimization } from "@/types/optimizations";

type OptimizationRunHeaderProps = {
  optimization?: Optimization | null;
};

const OptimizationRunHeader: React.FC<OptimizationRunHeaderProps> = ({
  optimization,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  if (!optimization) {
    return (
      <div className="comet-body-s-accented text-foreground-secondary">
        Optimization run
      </div>
    );
  }

  return (
    <div className="flex items-center gap-2">
      <span className="comet-body-s text-foreground-secondary">
        Optimization run
      </span>
      <Button variant="link" size="sm" className="h-auto p-0" asChild>
        <Link
          to="/$workspaceName/optimizations/$optimizationId/compare"
          params={{
            workspaceName,
            optimizationId: optimization.id,
          }}
          search={{ optimizations: [optimization.id] }}
          target="_blank"
        >
          {optimization.name}
          <ExternalLink className="ml-1.5 size-3.5 shrink-0" />
        </Link>
      </Button>
    </div>
  );
};

export default OptimizationRunHeader;
