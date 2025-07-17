import React from "react";
import ExpandableSearchInput from "@/components/shared/ExpandableSearchInput/ExpandableSearchInput";

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
    <div className="flex min-w-[200px] max-w-[60%] flex-auto justify-end overflow-hidden">
      <ExpandableSearchInput
        value={searchValue}
        placeholder="Search..."
        buttonVariant="ghost"
        size="sm"
        onChange={onSearch}
        onPrev={onPrev}
        onNext={onNext}
      />
    </div>
  );
};

export default SyntaxHighlighterSearch;
