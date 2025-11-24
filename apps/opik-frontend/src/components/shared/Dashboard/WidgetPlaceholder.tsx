import React from "react";
import { Card, CardContent } from "@/components/ui/card";

interface WidgetPlaceholderProps {
  type: string;
}

const WidgetPlaceholder: React.FC<WidgetPlaceholderProps> = ({ type }) => {
  return (
    <Card className="h-full">
      <CardContent className="flex h-full items-center justify-center p-6">
        <div className="text-center text-muted-foreground">
          <p className="text-base font-medium">{type} widget</p>
          <p className="mt-1 text-sm">Coming in Phase 1-3</p>
        </div>
      </CardContent>
    </Card>
  );
};

export default WidgetPlaceholder;
