import React from "react";

type CodeSectionTitleProps = {
  children: React.ReactNode;
};

const CodeSectionTitle: React.FC<CodeSectionTitleProps> = ({ children }) => (
  <div className="comet-body-s-accented md:comet-body-s mb-2 overflow-x-auto whitespace-nowrap">
    {children}
  </div>
);

export default CodeSectionTitle;
