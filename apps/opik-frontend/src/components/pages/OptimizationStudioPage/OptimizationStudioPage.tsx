import React from "react";
import { Button } from "@/components/ui/button";
import { Zap } from "lucide-react";
import { Link, Navigate } from "@tanstack/react-router";
import useAppStore from "@/store/AppStore";
import useOptimizationsList from "@/api/optimizations/useOptimizationsList";
import Loader from "@/components/shared/Loader/Loader";
import { ACTIVE_OPTIMIZATION_FILTER } from "@/lib/optimizations";

// TODO lala breadcrumbs is not working as expected
const OptimizationStudioPage = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const { data, isPending } = useOptimizationsList({
    workspaceName,
    page: 1,
    size: 1,
    filters: ACTIVE_OPTIMIZATION_FILTER,
  });

  if (isPending) {
    return <Loader />;
  }

  if (data && data.content.length > 0) {
    const activeOptimization = data.content[0];
    return (
      <Navigate
        to="/$workspaceName/optimization-studio/run"
        params={{ workspaceName }}
        search={{ optimizationId: activeOptimization.id }}
      />
    );
  }

  return (
    <div className="flex min-h-[calc(100vh-200px)] items-center justify-center">
      <div className="flex w-full max-w-2xl flex-col items-center text-center">
        <h1 className="comet-title-xl mb-4">Optimization studio</h1>
        <p className="comet-body-s mb-8 text-muted-slate">
          Test multiple variations for your agent or prompt to find the best one
          based on your metrics.
        </p>
        <Button size="default" asChild>
          <Link
            to="/$workspaceName/optimization-studio/run"
            params={{ workspaceName }}
          >
            <Zap className="mr-2 size-4" />
            Optimize your prompt
          </Link>
        </Button>
      </div>
    </div>
  );
};

export default OptimizationStudioPage;
