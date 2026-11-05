import React, { useRef, useState } from "react";
import isEqual from "lodash/isEqual";

import { DatasetItemColumn } from "@/types/datasets";
import DatasetFieldsEditor, { FieldFocus } from "./DatasetFieldsEditor";
import {
  DatasetFieldRow,
  dataToFieldRows,
  fieldRowsToData,
} from "./datasetFieldRows";

type DatasetItemFieldsAutosaveFormProps = {
  data: Record<string, unknown>;
  columns: DatasetItemColumn[];
  onDataChange: (data: Record<string, unknown>) => void;
};

// Rows are seeded once per record (callers key this by item id); re-seeding
// from the store on every autosave would reset ids and drop focus mid-typing.
const DatasetItemFieldsAutosaveForm: React.FunctionComponent<
  DatasetItemFieldsAutosaveFormProps
> = ({ data, columns, onDataChange }) => {
  const [rows, setRows] = useState<DatasetFieldRow[]>(() =>
    dataToFieldRows(data, columns),
  );
  const [focus, setFocus] = useState<FieldFocus>(null);
  const lastDataRef = useRef(fieldRowsToData(rows, { dropEmpty: false }));

  const handleRowsChange = (next: DatasetFieldRow[]) => {
    setRows(next);
    // Adding a still-unnamed field shouldn't mark the record as edited.
    const nextData = fieldRowsToData(next, { dropEmpty: false });
    if (isEqual(nextData, lastDataRef.current)) return;
    lastDataRef.current = nextData;
    onDataChange(nextData);
  };

  return (
    <DatasetFieldsEditor
      rows={rows}
      focus={focus}
      removeTooltip="Remove field from this record"
      onRowsChange={handleRowsChange}
      onFocusChange={setFocus}
    />
  );
};

export default DatasetItemFieldsAutosaveForm;
