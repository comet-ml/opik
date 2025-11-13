import React from "react";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Zap, BookOpen } from "lucide-react";
import { Link, Navigate } from "@tanstack/react-router";
import useAppStore from "@/store/AppStore";
import useOptimizationsList from "@/api/optimizations/useOptimizationsList";
import Loader from "@/components/shared/Loader/Loader";
import { ACTIVE_OPTIMIZATION_FILTER } from "@/lib/optimizations";
import { DEMO_TEMPLATES } from "@/constants/optimizations";

// TODO lala breadcrumbs is not working as expected
const OptimizationStudioPage = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const { data, isPending } = useOptimizationsList({
    workspaceName,
    page: 1,
    size: 1,
    studioOnly: true,
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
      <div className="flex w-full max-w-4xl flex-col items-center">
        <div className="mb-12 flex w-full max-w-2xl flex-col items-center text-center">
          <h1 className="comet-title-xl mb-4">Optimization studio</h1>
          <p className="comet-body-s mb-8 text-muted-foreground">
            Test multiple variations for your agent or prompt to find the best
            one based on your metrics.
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

        <div className="w-full">
          <div className="mb-4 flex items-center gap-2">
            <BookOpen className="size-5 text-muted-foreground" />
            <h2 className="comet-title-m">Demo templates</h2>
          </div>
          <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
            {DEMO_TEMPLATES.map((template) => (
              <Card
                key={template.id}
                className="cursor-pointer transition-all hover:border-foreground"
              >
                <Link
                  to="/$workspaceName/optimization-studio/run"
                  params={{ workspaceName }}
                  search={{ template: template.id }}
                  className="block"
                >
                  <CardHeader className="pb-3">
                    <CardTitle className="comet-body-m-accented">
                      {template.title}
                    </CardTitle>
                    <CardDescription className="comet-body-s">
                      {template.description}
                    </CardDescription>
                  </CardHeader>
                  <CardContent className="pt-0">
                    <div className="flex flex-wrap gap-2 text-xs text-muted-foreground">
                      <span className="rounded bg-secondary px-2 py-1">
                        {template.studio_config?.optimizer.type}
                      </span>
                      <span className="rounded bg-secondary px-2 py-1">
                        {template.studio_config?.evaluation.metrics[0]?.type}
                      </span>
                      <span className="rounded bg-secondary px-2 py-1">
                        {template.studio_config?.llm_model.name.split("/")[1]}
                      </span>
                    </div>
                  </CardContent>
                </Link>
              </Card>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
};

export default OptimizationStudioPage;
