import React from "react";
import { useNavigate } from "@tanstack/react-router";
import { Button } from "@/components/ui/button";
import useAppStore from "@/store/AppStore";

const OptimizationStudioHomePage = () => {
  const navigate = useNavigate();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const handleOptimizeClick = () => {
    navigate({
      to: "/$workspaceName/optimization-studio/run",
      params: { workspaceName },
    });
  };

  return (
    <div className="flex w-full min-w-fit flex-col items-center justify-center pb-12 pt-10">
      <h1 className="comet-title-xl text-center">Optimization Studio</h1>
      <div className="comet-body-s m-auto mt-4 w-[468px] text-center text-muted-slate">
        Test multiple variations for your agent or prompt to find the best one
        based on your metrics.
      </div>
      <Button
        variant="default"
        className="mt-6"
        size="sm"
        onClick={handleOptimizeClick}
      >
        Optimize your prompts
      </Button>
    </div>
  );
};

export default OptimizationStudioHomePage;
