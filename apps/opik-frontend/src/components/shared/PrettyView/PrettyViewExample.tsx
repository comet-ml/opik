import React from "react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { PrettyViewContainer } from "./index";
import { Trace } from "@/types/traces";

interface PrettyViewExampleProps {
  trace: Trace;
  className?: string;
}

/**
 * Example component demonstrating the new pretty view system
 * This shows how the system automatically detects providers and formats content
 */
const PrettyViewExample: React.FC<PrettyViewExampleProps> = ({
  trace,
  className,
}) => {
  return (
    <div className={className}>
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            Pretty View Example
            <Badge variant="outline">New</Badge>
          </CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          <div>
            <h4 className="comet-body-s-accented mb-2">Input</h4>
            <PrettyViewContainer data={trace} type="input" />
          </div>
          
          <div>
            <h4 className="comet-body-s-accented mb-2">Output</h4>
            <PrettyViewContainer data={trace} type="output" />
          </div>
        </CardContent>
      </Card>
    </div>
  );
};

export default PrettyViewExample;
