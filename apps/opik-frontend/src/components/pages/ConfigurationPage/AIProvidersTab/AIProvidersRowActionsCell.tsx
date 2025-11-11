import React, { useCallback, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { CellContext } from "@tanstack/react-table";
import { MoreHorizontal, Pencil, Trash } from "lucide-react";

import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Button } from "@/components/ui/button";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import ManageAIProviderDialog from "@/components/pages-shared/llm/ManageAIProviderDialog/ManageAIProviderDialog";
import { ProviderObject } from "@/types/providers";
import useProviderKeysDeleteMutation from "@/api/provider-keys/useProviderKeysDeleteMutation";

const AIProvidersRowActionsCell: React.FunctionComponent<
  CellContext<ProviderObject, unknown>
> = (context) => {
  const { t } = useTranslation();
  const resetKeyRef = useRef(0);
  const providerKey = context.row.original;
  const [open, setOpen] = useState<boolean | number>(false);

  const { mutate: deleteProviderKey } = useProviderKeysDeleteMutation();

  const deleteProviderKeyHandler = useCallback(() => {
    deleteProviderKey({
      providerId: providerKey.id,
    });
  }, [providerKey.id, deleteProviderKey]);

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="justify-end p-0"
      stopClickPropagation
    >
      <ManageAIProviderDialog
        key={`edit-${resetKeyRef.current}`}
        providerKey={providerKey}
        open={open === 2}
        setOpen={setOpen}
      />
      <ConfirmDialog
        key={`delete-${resetKeyRef.current}`}
        open={open === 1}
        setOpen={setOpen}
        onConfirm={deleteProviderKeyHandler}
        title={t("configuration.aiProviders.rowActions.deleteTitle")}
        description={t("configuration.aiProviders.rowActions.deleteDescription")}
        confirmText={t("configuration.aiProviders.rowActions.deleteConfirm")}
        confirmButtonVariant="destructive"
      />
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="minimal" size="icon" className="-mr-2.5">
            <span className="sr-only">Actions menu</span>
            <MoreHorizontal className="size-4" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-52">
          <DropdownMenuItem
            onClick={() => {
              setOpen(2);
              resetKeyRef.current = resetKeyRef.current + 1;
            }}
          >
            <Pencil className="mr-2 size-4" />
            {t("configuration.aiProviders.rowActions.edit")}
          </DropdownMenuItem>
          <DropdownMenuItem
            onClick={() => {
              setOpen(1);
              resetKeyRef.current = resetKeyRef.current + 1;
            }}
          >
            <Trash className="mr-2 size-4" />
            {t("configuration.aiProviders.rowActions.delete")}
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </CellWrapper>
  );
};

export default AIProvidersRowActionsCell;
