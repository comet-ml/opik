import React, { useEffect, useMemo, useState } from "react";
import sortBy from "lodash/sortBy";
import { Plus, X } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { CategoricalFeedbackDefinition } from "@/types/feedback-definitions";

type CategoricalFeedbackDefinitionDetailsProps = {
  onChange: (details: CategoricalFeedbackDefinition["details"]) => void;
  details?: CategoricalFeedbackDefinition["details"];
};

type CategoryField = {
  name?: string;
  value?: number;
};

function categoryFieldsToDetails(
  categoryFields: CategoryField[],
): CategoricalFeedbackDefinition["details"] {
  const categories = {} as Record<string, number>;

  categoryFields.forEach((categoryField) => {
    if (categoryField.name && categoryField.value !== undefined) {
      categories[categoryField.name] = categoryField.value;
    }
  });

  return { categories };
}

const CategoricalFeedbackDefinitionDetails: React.FunctionComponent<
  CategoricalFeedbackDefinitionDetailsProps
> = ({ onChange, details }) => {
  const [categories, setCategories] = useState<CategoryField[]>(
    details?.categories
      ? sortBy(
          Object.entries(details?.categories).map(([name, value]) => ({
            name,
            value,
          })),
          "name",
        )
      : [{}, {}],
  );

  const categoricalDetails = useMemo(
    () => categoryFieldsToDetails(categories),
    [categories],
  );

  useEffect(() => {
    onChange(categoricalDetails);
  }, [categoricalDetails, onChange]);

  return (
    <>
      <div className="flex flex-col gap-4">
        <Label>Categories</Label>
      </div>
      {categories.map((category, index) => {
        return (
          <div className="flex flex-row items-center gap-4" key={index}>
            <Input
              placeholder="Name"
              value={category.name}
              onChange={(event) => {
                setCategories((prevCategories) => {
                  return prevCategories.map((category, categoryIndex) => {
                    if (categoryIndex !== index) {
                      return category;
                    }

                    return { ...category, name: event.target.value };
                  });
                });
              }}
            />
            <Input
              placeholder="0.0"
              value={category.value}
              type="number"
              onChange={(event) => {
                setCategories((prevCategories) => {
                  return prevCategories.map((prevCategory) => {
                    if (prevCategory !== category) {
                      return prevCategory;
                    }

                    return {
                      ...prevCategory,
                      value: Number(event.target.value),
                    };
                  });
                });
              }}
            />

            {categories.length > 1 ? (
              <Button
                variant="ghost"
                size="icon-xs"
                onClick={() => {
                  setCategories((prevCategories) => {
                    return prevCategories.filter(
                      (prevCategory) => prevCategory !== category,
                    );
                  });
                }}
              >
                <X className="size-4" />
              </Button>
            ) : null}
          </div>
        );
      })}
      <div>
        <Button
          variant="secondary"
          size="sm"
          onClick={() => {
            setCategories((categories) => [...categories, {}]);
          }}
        >
          <Plus className="mr-2 size-4" /> Add category
        </Button>
      </div>
    </>
  );
};

export default CategoricalFeedbackDefinitionDetails;
