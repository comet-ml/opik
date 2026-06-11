import React from "react";
import { Waypoints } from "lucide-react";
import NoData from "@/shared/NoData/NoData";

const PatternsTab: React.FC = () => {
  return (
    <NoData
      icon={<Waypoints className="size-6 text-muted-slate" />}
      title="Patterns coming soon"
      message="Recurring behavioral patterns across your traces will show up here."
      className="h-[400px]"
    />
  );
};

export default PatternsTab;
