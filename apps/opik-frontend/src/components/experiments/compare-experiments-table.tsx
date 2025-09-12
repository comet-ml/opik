import { StickyHeader } from "@/components/ui/table/sticky-header";

export function CompareExperimentsTable() {
  return (
    <div className="relative overflow-auto">
      <table>
        <StickyHeader>{/* Your existing header content */}</StickyHeader>
        <tbody>{/* Your existing table body content */}</tbody>
      </table>
    </div>
  );
}
