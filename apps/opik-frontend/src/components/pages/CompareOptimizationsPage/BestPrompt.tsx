import React, { useMemo } from "react";
import { Link } from "@tanstack/react-router";
import { ArrowRight } from "lucide-react";
import isUndefined from "lodash/isUndefined";
import isObject from "lodash/isObject";
import get from "lodash/get";

import { OPTIMIZATION_PROMPT_KEY } from "@/constants/experiments";
import useAppStore from "@/store/AppStore";
import { Experiment } from "@/types/datasets";
import { Optimization } from "@/types/optimizations";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { formatNumericData, toString } from "@/lib/utils";
import ColoredTagNew from "@/components/shared/ColoredTag/ColoredTagNew";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import PercentageTrend from "@/components/pages/CompareOptimizationsPage/PercentageTrend";

type BestPromptProps = {
  optimization: Optimization;
  experiment: Experiment;
  scoreMap: Record<string, { score: number; percentage?: number }>;
};

const BestPrompt: React.FC<BestPromptProps> = ({
  optimization,
  experiment,
  scoreMap,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const { score, percentage } = useMemo(() => {
    const retVal: {
      score?: number;
      percentage?: number;
    } = {
      score: undefined,
      percentage: undefined,
    };

    const scoreObject = scoreMap[experiment.id];
    if (!scoreObject) return retVal;

    retVal.score = scoreObject.score;
    retVal.percentage = scoreObject.percentage;

    return retVal;
  }, [experiment.id, scoreMap]);

  const prompt = useMemo(() => {
    const val = get(experiment.metadata ?? {}, OPTIMIZATION_PROMPT_KEY, "-");

    return isObject(val) ? JSON.stringify(val, null, 2) : toString(val);
  }, [experiment]);

  return (
    <Card className="h-[224px] w-[280px]">
      <CardHeader className="gap-y-0.5 px-5">
        <CardTitle className="comet-body-s-accented">Best prompt</CardTitle>
        <CardDescription className="!mt-0">
          <ColoredTagNew
            label={optimization.objective_name}
            size="sm"
            className="px-0"
          />
        </CardDescription>
      </CardHeader>
      <CardContent className="px-5 pb-4">
        <div className="-mt-0.5 flex h-10 flex-row items-end gap-3">
          <div className="comet-title-xl text-4xl text-foreground-secondary">
            {isUndefined(score) ? "-" : formatNumericData(score)}
          </div>
          <PercentageTrend percentage={percentage} />
        </div>
        <TooltipWrapper content={prompt}>
          <div className="comet-body-s mt-5 line-clamp-2 h-11 text-light-slate">
            {prompt}
          </div>
        </TooltipWrapper>
        <div className="flex justify-end pt-1">
          <Link
            to="/$workspaceName/optimizations/$datasetId/$optimizationId/compare"
            params={{
              workspaceName,
              datasetId: experiment.dataset_id,
              optimizationId: optimization.id,
            }}
            search={{ trials: [experiment.id] }}
          >
            <Button variant="ghost" className="flex items-center gap-1 pr-0">
              View details <ArrowRight className="size-4" />
            </Button>
          </Link>
        </div>
      </CardContent>
    </Card>
  );
};

export default BestPrompt;
