import React from "react";
import {
  DndContext,
  DragEndEvent,
  MouseSensor,
  closestCenter,
  useSensor,
  useSensors,
} from "@dnd-kit/core";
import { restrictToVerticalAxis } from "@dnd-kit/modifiers";
import {
  SortableContext,
  arrayMove,
  verticalListSortingStrategy,
} from "@dnd-kit/sortable";
import { Plus } from "lucide-react";
import { Button } from "@/ui/button";
import PathSourcePicker from "@/v2/pages-shared/traces/AddToDatasetDialog/PathSourcePicker";
import { JsonObject } from "@/types/shared";
import { Trace } from "@/types/traces";
import FieldRow from "./FieldRow";
import { buildFieldsTree, labelFromPath } from "./annotatorViewConfig";
import {
  AnnotatorFieldDraft,
  AnnotatorViewDraft,
  toFieldDraft,
} from "./useAnnotatorViewDraft";

const ADD_FIELD_TRIGGER_HEIGHT = 32;

type FieldsSectionProps = {
  sample?: Trace;
  isSamplePending: boolean;
  value: AnnotatorViewDraft;
  onChange: (value: AnnotatorViewDraft) => void;
};

const FieldsSection: React.FC<FieldsSectionProps> = ({
  sample,
  isSamplePending,
  value,
  onChange,
}) => {
  const sensors = useSensors(
    useSensor(MouseSensor, { activationConstraint: { distance: 2 } }),
  );
  const treeData = (
    sample
      ? buildFieldsTree(
          {
            input: sample.input,
            output: sample.output,
            metadata: sample.metadata,
          },
          sample.feedback_scores,
        )
      : {}
  ) as JsonObject;

  const setFields = (fields: AnnotatorFieldDraft[]) =>
    onChange({ ...value, fields });

  const handleDragEnd = ({ active, over }: DragEndEvent) => {
    if (!over || active.id === over.id) return;
    const oldIndex = value.fields.findIndex((field) => field.id === active.id);
    const newIndex = value.fields.findIndex((field) => field.id === over.id);
    setFields(arrayMove(value.fields, oldIndex, newIndex));
  };

  return (
    <div className="overflow-hidden rounded-md border border-border bg-soft-background">
      <div className="flex flex-col gap-0.5 border-b border-border p-3">
        <span className="comet-body-s-accented">Fields</span>
        <span className="comet-body-xs text-muted-slate">
          Choose which trace field goes into each block of the annotator view.
        </span>
      </div>
      <div className="flex flex-col gap-1.5 p-2">
        <DndContext
          sensors={sensors}
          collisionDetection={closestCenter}
          modifiers={[restrictToVerticalAxis]}
          onDragEnd={handleDragEnd}
        >
          <SortableContext
            items={value.fields}
            strategy={verticalListSortingStrategy}
          >
            {value.fields.map((field) => (
              <FieldRow
                key={field.id}
                field={field}
                treeData={treeData}
                isPending={isSamplePending}
                onChange={(next) =>
                  setFields(
                    value.fields.map((item) =>
                      item.id === next.id ? next : item,
                    ),
                  )
                }
                onRemove={() =>
                  setFields(value.fields.filter((item) => item.id !== field.id))
                }
              />
            ))}
          </SortableContext>
        </DndContext>
        <PathSourcePicker
          treeData={treeData}
          isPending={isSamplePending}
          sideOffset={-ADD_FIELD_TRIGGER_HEIGHT}
          onSelect={(path) =>
            setFields([
              ...value.fields,
              toFieldDraft({ label: labelFromPath(path), path }),
            ])
          }
          trigger={
            <div className="flex h-8 cursor-pointer items-center justify-center rounded-md border border-dashed border-border bg-soft-background data-[state=open]:invisible">
              <Button variant="ghost" size="2xs">
                <Plus className="size-3" />
                Add field
              </Button>
            </div>
          }
        />
      </div>
    </div>
  );
};

export default FieldsSection;
