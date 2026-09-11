import React from "react";

interface PopoverHeaderProps {
  searchQuery: string;
  pathToExpand: string | null;
  searchTerm: string;
  isArrayAccess: boolean;
}

const PopoverHeader: React.FC<PopoverHeaderProps> = ({
  searchQuery,
  pathToExpand,
  searchTerm,
  isArrayAccess,
}) => {
  if (!searchQuery.trim()) {
    return (
      <div className="border-b px-4 py-3">
        <h4 className="comet-body-xs-accented">Select a variable</h4>
        <p className="comet-body-xs mt-1 text-light-slate">
          Start typing to filter, use <span className="font-mono">.</span> for
          objects, <span className="font-mono">[</span> for arrays
        </p>
      </div>
    );
  }

  const renderTitle = () => {
    if (!pathToExpand) {
      return (
        <>
          Filtering: <span className="font-mono">{searchQuery}</span>
        </>
      );
    }

    return (
      <>
        {isArrayAccess ? "Array" : "Path"}:{" "}
        <span className="font-mono">{pathToExpand}</span>
        {isArrayAccess && !searchTerm && (
          <span className="text-light-slate"> → select an index</span>
        )}
        {searchTerm && (
          <span className="text-light-slate">
            {" "}
            → {isArrayAccess ? "index" : "filtering by"} &quot;{searchTerm}
            &quot;
          </span>
        )}
      </>
    );
  };

  const renderHint = () => {
    if (isArrayAccess) {
      return "Type an index number to filter array elements";
    }

    return (
      <>
        Type <span className="font-mono">.</span> to expand into nested fields,{" "}
        <span className="font-mono">[</span> for arrays
      </>
    );
  };

  return (
    <div className="border-b px-4 py-3">
      <h4 className="comet-body-xs-accented">{renderTitle()}</h4>
      <p className="comet-body-xs mt-1 text-light-slate">{renderHint()}</p>
    </div>
  );
};

export default PopoverHeader;
