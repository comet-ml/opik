import React from "react";

type ExperimentChartTooltipHeaderProps = {
  entityName: string;
  createdDate?: string;
  changeDescription?: string;
  entityNameClassName?: string;
};

const ExperimentChartTooltipHeader: React.FC<
  ExperimentChartTooltipHeaderProps
> = ({
  entityName,
  createdDate,
  changeDescription,
  entityNameClassName = "truncate",
}) => (
  <>
    <div className={`comet-body-xs-accented mb-0.5 ${entityNameClassName}`}>
      {entityName}
    </div>
    {createdDate && (
      <div className="comet-body-xs mb-1 text-light-slate">{createdDate}</div>
    )}
    {changeDescription && (
      <div className="comet-body-xs mb-1 truncate text-light-slate">
        {changeDescription}
      </div>
    )}
  </>
);

export default ExperimentChartTooltipHeader;
