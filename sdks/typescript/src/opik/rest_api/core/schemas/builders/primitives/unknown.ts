import { SchemaType } from "../../Schema";
import { createIdentitySchemaCreator } from "../../utils/createIdentitySchemaCreator";

export const unknown = createIdentitySchemaCreator<unknown>(SchemaType.UNKNOWN, (value) => ({ ok: true, value }));
