import React from "react";

export const filterItems = (items: string[], query: string): string[] => {
  const q = query.trim().toLowerCase();
  if (q === "") return items;
  return items.filter((item) => item.toLowerCase().includes(q));
};

export const highlightMatch = (
  text: string,
  query: string,
): React.ReactNode => {
  if (query === "") return text;
  const lower = text.toLowerCase();
  const q = query.toLowerCase();
  const idx = lower.indexOf(q);
  if (idx === -1) return text;
  return (
    <>
      {text.slice(0, idx)}
      <span className="font-medium text-primary-active">
        {text.slice(idx, idx + query.length)}
      </span>
      {text.slice(idx + query.length)}
    </>
  );
};
