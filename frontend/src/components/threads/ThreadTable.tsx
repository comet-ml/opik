import React from "react";
import { useThreadsSelection } from "../../store/threadsSelection";
import { batchTagThreads } from "../../api/threads";
import TagSelector from "../tags/TagSelector";

type Thread = {
  id: string;
  title: string;
  tags: string[];
};

type Props = {
  threads: Thread[];
  onTagsUpdated?: (updatedIds: string[], add: string[], remove: string[]) => void;
  showToast?: (msg: string, type?: "success" | "warning" | "error") => void;
};

export default function ThreadTable({ threads, onTagsUpdated, showToast }: Props) {
  const { selected, select, clear, getSelectedArray } = useThreadsSelection();

  const applyTags = async (add: string[], remove: string[]) => {
    const ids = getSelectedArray();
    if (!ids.length) return;
    try {
      const res = await batchTagThreads(ids, add, remove);
      if (onTagsUpdated) onTagsUpdated(res.updated, add, remove);
      if (showToast) {
        if (res.failed.length) {
          showToast(
            `Updated ${res.updated.length} threads; ${res.failed.length} failed`,
            "warning"
          );
        } else {
          showToast(`Tags updated for ${res.updated.length} threads`, "success");
        }
      }
    } catch (e) {
      showToast && showToast("Bulk tagging failed", "error");
    } finally {
      clear();
    }
  };

  return (
    <div>
      <table className="thread-table">
        <thead>
          <tr>
            <th>Select</th>
            <th>Thread</th>
            <th>Tags</th>
          </tr>
        </thead>
        <tbody>
          {threads.map((t) => {
            const isSelected = selected.has(t.id);
            return (
              <tr key={t.id}>
                <td>
                  <input
                    type="checkbox"
                    checked={isSelected}
                    onChange={(e) => select(t.id, e.target.checked)}
                    aria-label={`Select ${t.title}`}
                  />
                </td>
                <td>{t.title}</td>
                <td>{t.tags.join(", ")}</td>
              </tr>
            );
          })}
        </tbody>
      </table>

      {selected.size > 0 && (
        <div className="bulk-action">
          <TagSelector onApply={applyTags} />
        </div>
      )}
    </div>
  );
}