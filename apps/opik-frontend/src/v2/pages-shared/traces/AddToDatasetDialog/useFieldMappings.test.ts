import { describe, it, expect } from "vitest";
import { act, renderHook } from "@testing-library/react";
import { useState } from "react";

import useFieldMappings from "./useFieldMappings";
import { EnrichmentOptions } from "./useAddToDatasetForm";
import { MAX_FIELD_NAME_LENGTH } from "./fieldMappingTypes";

const ALL_OFF: EnrichmentOptions = {
  includeSpans: false,
  includeTags: false,
  includeFeedbackScores: false,
  includeComments: false,
  includeUsage: false,
  includeMetadata: false,
};

const renderMappings = (hasOnlySpans = false, datasetColumns: string[] = []) =>
  renderHook(() => {
    const [enrichmentOptions, setEnrichmentOptions] =
      useState<EnrichmentOptions>(ALL_OFF);
    return {
      enrichmentOptions,
      ...useFieldMappings({
        enrichmentOptions,
        setEnrichmentOptions,
        hasOnlySpans,
        datasetColumns,
      }),
    };
  });

describe("useFieldMappings", () => {
  it("seeds the two fixed rows with their default paths", () => {
    const { result } = renderMappings();

    expect(result.current.fixedRows.map((row) => row.id)).toEqual([
      "input",
      "expected_output",
    ]);
    expect(result.current.fixedRows.map((row) => row.name)).toEqual([
      "input",
      "expected_output",
    ]);
    expect(result.current.fieldMappings).toEqual({
      input: "input",
      expected_output: "output",
    });
  });

  it("maps a fixed row once another path is chosen", () => {
    const { result } = renderMappings();

    act(() => result.current.setFixedPath("input", "input.input_text"));

    expect(result.current.fieldMappings).toEqual({
      input: "input.input_text",
      expected_output: "output",
    });
  });

  it("names a custom row after the last segment of its path", () => {
    const { result } = renderMappings();

    act(() => result.current.addRow("output.choices[0].message"));

    expect(result.current.customRows[0].name).toBe("message");
    expect(result.current.fieldMappings).toEqual({
      input: "input",
      expected_output: "output",
      message: "output.choices[0].message",
    });
  });

  it("omits a custom row while its name is blank", () => {
    const { result } = renderMappings();

    act(() => result.current.addRow("input.tone"));
    const rowId = result.current.customRows[0].id;

    act(() => result.current.renameRow(rowId, ""));
    expect(result.current.fieldMappings).toEqual({
      input: "input",
      expected_output: "output",
    });

    act(() => result.current.renameRow(rowId, "tone"));
    expect(result.current.fieldMappings).toEqual({
      input: "input",
      expected_output: "output",
      tone: "input.tone",
    });
  });

  it("drops a removed custom row from the mappings", () => {
    const { result } = renderMappings();

    act(() => result.current.addRow("input.tone"));
    const rowId = result.current.customRows[0].id;

    act(() => result.current.removeRow(rowId));

    expect(result.current.customRows).toHaveLength(0);
    expect(result.current.fieldMappings).toEqual({
      input: "input",
      expected_output: "output",
    });
  });

  it("turns a quick-add chip into a managed row without adding a mapping", () => {
    const { result } = renderMappings();

    act(() => result.current.setManagedOption("includeTags", true));

    expect(result.current.enrichmentOptions.includeTags).toBe(true);
    expect(result.current.managedRows.map((row) => row.option)).toContain(
      "includeTags",
    );
    expect(result.current.managedRows.map((row) => row.name)).toContain("tags");
    expect(result.current.fieldMappings).toEqual({
      input: "input",
      expected_output: "output",
    });
    expect(
      result.current.availableChips.map((chip) => chip.option),
    ).not.toContain("includeTags");
  });

  it("hides the nested spans chip for spans", () => {
    const { result } = renderMappings(true);

    expect(
      result.current.availableChips.map((chip) => chip.option),
    ).not.toContain("includeSpans");
  });

  it("flags a duplicate name and keeps it out of the mappings", () => {
    const { result } = renderMappings();

    act(() => result.current.addRow("input.tone"));
    act(() => result.current.addRow("output.tone"));
    const [first, second] = result.current.customRows;

    expect(result.current.rowErrors[second.id]).toBe("duplicate");
    expect(result.current.fieldMappings).toEqual({
      input: "input",
      expected_output: "output",
      tone: "input.tone",
    });
    expect(result.current.rowErrors[first.id]).toBeUndefined();
  });

  it("flags a name that collides with a fixed field", () => {
    const { result } = renderMappings();

    act(() => result.current.addRow("input.tone"));
    const rowId = result.current.customRows[0].id;
    act(() => result.current.renameRow(rowId, "input"));

    expect(result.current.rowErrors[rowId]).toBe("duplicate");
    expect(result.current.fieldMappings).toEqual({
      input: "input",
      expected_output: "output",
    });
  });

  it("keeps basic mode available while nothing deviates from the defaults", () => {
    const { result } = renderMappings();

    expect(result.current.canUseBasicMode).toBe(true);

    act(() => result.current.setManagedOption("includeTags", true));

    expect(result.current.canUseBasicMode).toBe(true);
  });

  it("locks advanced mode once a fixed path leaves its default", () => {
    const { result } = renderMappings();

    act(() => result.current.setFixedPath("input", "input.input_text"));
    expect(result.current.canUseBasicMode).toBe(false);

    act(() => result.current.setFixedPath("input", "input"));
    expect(result.current.canUseBasicMode).toBe(true);
  });

  it("locks advanced mode while a custom row exists", () => {
    const { result } = renderMappings();

    act(() => result.current.addRow("input.tone"));
    expect(result.current.canUseBasicMode).toBe(false);

    act(() => result.current.removeRow(result.current.customRows[0].id));
    expect(result.current.canUseBasicMode).toBe(true);
  });

  it("flags a name that collides with a managed field", () => {
    const { result } = renderMappings();

    act(() => result.current.setManagedOption("includeUsage", true));
    act(() => result.current.addRow("usage.total_tokens"));
    const rowId = result.current.customRows[0].id;
    act(() => result.current.renameRow(rowId, "usage"));

    expect(result.current.rowErrors[rowId]).toBe("duplicate");
    expect(result.current.isValid).toBe(false);
  });

  it("frees a managed name once its row is removed", () => {
    const { result } = renderMappings();

    act(() => result.current.setManagedOption("includeUsage", true));
    act(() => result.current.addRow("usage.total_tokens"));
    const rowId = result.current.customRows[0].id;
    act(() => result.current.renameRow(rowId, "usage"));
    act(() => result.current.setManagedOption("includeUsage", false));

    expect(result.current.rowErrors[rowId]).toBeUndefined();
    expect(result.current.fieldMappings.usage).toBe("usage.total_tokens");
  });

  it("flags an over long name", () => {
    const { result } = renderMappings();

    act(() => result.current.addRow("input.tone"));
    const rowId = result.current.customRows[0].id;
    act(() =>
      result.current.renameRow(rowId, "a".repeat(MAX_FIELD_NAME_LENGTH + 1)),
    );

    expect(result.current.rowErrors[rowId]).toBe("too_long");
    expect(result.current.fieldMappings).toEqual({
      input: "input",
      expected_output: "output",
    });
  });
  it("marks a freshly added row as touched so a bad name surfaces at once", () => {
    const { result } = renderMappings();

    act(() => result.current.addRow("input.tone"));

    expect(result.current.customRows[0].touched).toBe(true);
    expect(result.current.rowErrors[result.current.customRows[0].id]).toBe(
      undefined,
    );
  });

  it("offers dataset columns as chips, minus fixed and managed keys", () => {
    const { result } = renderMappings(false, [
      "input",
      "expected_output",
      "metadata",
      "bucket",
      "tone",
    ]);

    expect(result.current.datasetChips).toEqual(["bucket", "tone"]);
  });

  it("drops a dataset chip once a row uses its name and restores it after removal", () => {
    const { result } = renderMappings(false, ["bucket", "tone"]);

    let id = "";
    act(() => {
      id = result.current.addNamedRow("bucket");
    });

    expect(result.current.datasetChips).toEqual(["tone"]);

    act(() => result.current.removeRow(id));

    expect(result.current.datasetChips).toEqual(["bucket", "tone"]);
  });

  it("adds a named row without a path and keeps it out of the mappings", () => {
    const { result } = renderMappings(false, ["bucket"]);

    act(() => {
      result.current.addNamedRow("bucket");
    });

    expect(result.current.customRows).toHaveLength(1);
    expect(result.current.customRows[0].name).toBe("bucket");
    expect(result.current.customRows[0].path).toBeUndefined();
    expect(result.current.fieldMappings).toEqual({
      input: "input",
      expected_output: "output",
    });
  });

  it("appends a re-enabled managed row at the end instead of its chip position", () => {
    const { result } = renderMappings();

    act(() => result.current.setManagedOption("includeSpans", true));
    act(() => result.current.setManagedOption("includeTags", true));

    expect(result.current.additionalRows.map((row) => row.name)).toEqual([
      "spans",
      "tags",
    ]);

    act(() => result.current.setManagedOption("includeSpans", false));
    act(() => result.current.setManagedOption("includeSpans", true));

    expect(result.current.additionalRows.map((row) => row.name)).toEqual([
      "tags",
      "spans",
    ]);
  });

  it("appends a newly added custom row after the existing rows", () => {
    const { result } = renderMappings();

    act(() => result.current.setManagedOption("includeTags", true));
    act(() => {
      result.current.addRow("input.tone");
    });
    act(() => result.current.setManagedOption("includeUsage", true));

    expect(result.current.additionalRows.map((row) => row.name)).toEqual([
      "tags",
      "tone",
      "usage",
    ]);
  });

  it("blocks submit while a named row has no source path", () => {
    const { result } = renderMappings(false, ["bucket"]);

    act(() => {
      result.current.addNamedRow("bucket");
    });

    expect(result.current.rowErrors["custom-1"]).toBe("no_path");
    expect(result.current.isValid).toBe(false);

    act(() => result.current.setRowPath("custom-1", "input.bucket"));

    expect(result.current.rowErrors["custom-1"]).toBeUndefined();
    expect(result.current.isValid).toBe(true);
    expect(result.current.fieldMappings.bucket).toBe("input.bucket");
  });

  it("reports a blank name before a missing path", () => {
    const { result } = renderMappings();

    act(() => {
      result.current.addNamedRow("");
    });

    expect(result.current.rowErrors["custom-1"]).toBe("blank");
  });
});
