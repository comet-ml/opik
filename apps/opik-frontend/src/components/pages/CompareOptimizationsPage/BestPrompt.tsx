import React from "react";
import { Experiment } from "@/types/datasets";
import { Optimization } from "@/types/optimizations";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";

type BestPromptProps = {
  experiment?: Experiment;
  optimization?: Optimization;
};

const BestPrompt: React.FC<BestPromptProps> = ({
  experiment,
  optimization,
}) => {
  return (
    <Card className="h-[224px] w-[280px]">
      <CardHeader className="space-y-0.5 px-4 pt-3">
        <CardTitle className="comet-body-s-accented">Best prompt</CardTitle>
        <CardDescription className="comet-body-xs text-xs">
          Some description
        </CardDescription>
      </CardHeader>
      <CardContent className="px-4 pb-3">Hello world</CardContent>
    </Card>
  );
};

export default BestPrompt;
