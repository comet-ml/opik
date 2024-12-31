import { Schema, SchemaType } from "../../Schema";
import { createIdentitySchemaCreator } from "../../utils/createIdentitySchemaCreator";
import { getErrorMessageForIncorrectType } from "../../utils/getErrorMessageForIncorrectType";

export function enum_<U extends string, E extends U[]>(values: E): Schema<E[number], E[number]> {
    const validValues = new Set<string>(values);

    const schemaCreator = createIdentitySchemaCreator(
        SchemaType.ENUM,
        (value, { allowUnrecognizedEnumValues, breadcrumbsPrefix = [] } = {}) => {
            if (typeof value !== "string") {
                return {
                    ok: false,
                    errors: [
                        {
                            path: breadcrumbsPrefix,
                            message: getErrorMessageForIncorrectType(value, "string"),
                        },
                    ],
                };
            }

            if (!validValues.has(value) && !allowUnrecognizedEnumValues) {
                return {
                    ok: false,
                    errors: [
                        {
                            path: breadcrumbsPrefix,
                            message: getErrorMessageForIncorrectType(value, "enum"),
                        },
                    ],
                };
            }

            return {
                ok: true,
                value: value as U,
            };
        }
    );

    return schemaCreator();
}
