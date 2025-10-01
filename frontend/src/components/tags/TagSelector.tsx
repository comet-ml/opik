import React, { useState } from "react";

type Props = {
  onApply: (add: string[], remove: string[]) => void;
};

export default function TagSelector({ onApply }: Props) {
  const [add, setAdd] = useState<string>("");
  const [remove, setRemove] = useState<string>("");

  const parse = (s: string) =>
    s
      .split(",")
      .map((x) => x.trim())
      .filter(Boolean);

  return (
    <div className="bulk-tag-bar">
      <div className="inputs">
        <input
          aria-label="Tags to add"
          placeholder="Add tags (comma separated)"
          value={add}
          onChange={(e) => setAdd(e.target.value)}
        />
        <input
          aria-label="Tags to remove"
          placeholder="Remove tags (comma separated)"
          value={remove}
          onChange={(e) => setRemove(e.target.value)}
        />
      </div>
      <button
        className="apply-btn"
        onClick={() => onApply(parse(add), parse(remove))}
      >
        Apply
      </button>
    </div>
  );
}