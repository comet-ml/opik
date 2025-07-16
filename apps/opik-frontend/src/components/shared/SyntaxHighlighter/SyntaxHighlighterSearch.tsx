import React from "react";
import ExpandableSearchInput from "../ExpandableSearchInput/ExpandableSearchInput";

export interface SyntaxHighlighterSearchProps {
  searchValue?: string;
  onSearch: (value: string) => void;
  onPrev?: () => void;
  onNext?: () => void;
}

const SyntaxHighlighterSearch: React.FC<SyntaxHighlighterSearchProps> = ({
  searchValue,
  onSearch,
  onPrev = () => {},
  onNext = () => {},
}) => {
  return (
    <ExpandableSearchInput
      value={searchValue}
      placeholder="Search..."
      className="min-w-[200px] max-w-[60%] flex-auto justify-end overflow-hidden"
      buttonClassName="border-none bg-primary-foreground"
      inputClassName="h-7"
      onChange={onSearch}
      onPrev={onPrev}
      onNext={onNext}
    />
  );
};

export default SyntaxHighlighterSearch;
