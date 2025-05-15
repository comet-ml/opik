package com.comet.opik.infrastructure.llm.vertexai.internal;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
class GeminiSchema {

    private GeminiType type;
    private String format;
    private String description;
    private Boolean nullable;
    @JsonProperty("enum")
    private List<String> enumeration;
    private String maxItems;
    private Map<String, GeminiSchema> properties;
    private List<String> required;
    private GeminiSchema items;

    GeminiSchema(GeminiType type, String format, String description, Boolean nullable, List<String> enumeration,
            String maxItems, Map<String, GeminiSchema> properties, List<String> required, GeminiSchema items) {
        this.type = type;
        this.format = format;
        this.description = description;
        this.nullable = nullable;
        this.enumeration = enumeration;
        this.maxItems = maxItems;
        this.properties = properties;
        this.required = required;
        this.items = items;
    }

    public static GeminiSchemaBuilder builder() {
        return new GeminiSchemaBuilder();
    }

    public GeminiType getType() {
        return this.type;
    }

    public String getFormat() {
        return this.format;
    }

    public String getDescription() {
        return this.description;
    }

    public Boolean getNullable() {
        return this.nullable;
    }

    @JsonIgnore
    public List<String> getEnumeration() {
        return this.enumeration;
    }

    public String getMaxItems() {
        return this.maxItems;
    }

    public Map<String, GeminiSchema> getProperties() {
        return this.properties;
    }

    public List<String> getRequired() {
        return this.required;
    }

    public GeminiSchema getItems() {
        return this.items;
    }

    public void setType(GeminiType type) {
        this.type = type;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setNullable(Boolean nullable) {
        this.nullable = nullable;
    }

    public void setEnumeration(List<String> enumeration) {
        this.enumeration = enumeration;
    }

    public void setMaxItems(String maxItems) {
        this.maxItems = maxItems;
    }

    public void setProperties(Map<String, GeminiSchema> properties) {
        this.properties = properties;
    }

    public void setRequired(List<String> required) {
        this.required = required;
    }

    public void setItems(GeminiSchema items) {
        this.items = items;
    }

    public boolean equals(final Object o) {
        if (o == this) {
            return true;
        } else if (!(o instanceof GeminiSchema)) {
            return false;
        } else {
            GeminiSchema other = (GeminiSchema) o;
            if (!other.canEqual(this)) {
                return false;
            } else {
                Object this$type = this.getType();
                Object other$type = other.getType();
                if (this$type == null) {
                    if (other$type != null) {
                        return false;
                    }
                } else if (!this$type.equals(other$type)) {
                    return false;
                }

                Object this$format = this.getFormat();
                Object other$format = other.getFormat();
                if (this$format == null) {
                    if (other$format != null) {
                        return false;
                    }
                } else if (!this$format.equals(other$format)) {
                    return false;
                }

                Object this$description = this.getDescription();
                Object other$description = other.getDescription();
                if (this$description == null) {
                    if (other$description != null) {
                        return false;
                    }
                } else if (!this$description.equals(other$description)) {
                    return false;
                }

                Object this$nullable = this.getNullable();
                Object other$nullable = other.getNullable();
                if (this$nullable == null) {
                    if (other$nullable != null) {
                        return false;
                    }
                } else if (!this$nullable.equals(other$nullable)) {
                    return false;
                }

                Object this$enumeration = this.getEnumeration();
                Object other$enumeration = other.getEnumeration();
                if (this$enumeration == null) {
                    if (other$enumeration != null) {
                        return false;
                    }
                } else if (!this$enumeration.equals(other$enumeration)) {
                    return false;
                }

                Object this$maxItems = this.getMaxItems();
                Object other$maxItems = other.getMaxItems();
                if (this$maxItems == null) {
                    if (other$maxItems != null) {
                        return false;
                    }
                } else if (!this$maxItems.equals(other$maxItems)) {
                    return false;
                }

                Object this$properties = this.getProperties();
                Object other$properties = other.getProperties();
                if (this$properties == null) {
                    if (other$properties != null) {
                        return false;
                    }
                } else if (!this$properties.equals(other$properties)) {
                    return false;
                }

                Object this$required = this.getRequired();
                Object other$required = other.getRequired();
                if (this$required == null) {
                    if (other$required != null) {
                        return false;
                    }
                } else if (!this$required.equals(other$required)) {
                    return false;
                }

                Object this$items = this.getItems();
                Object other$items = other.getItems();
                if (this$items == null) {
                    if (other$items != null) {
                        return false;
                    }
                } else if (!this$items.equals(other$items)) {
                    return false;
                }

                return true;
            }
        }
    }

    protected boolean canEqual(final Object other) {
        return other instanceof GeminiSchema;
    }

    public int hashCode() {
        int PRIME = 59;
        int result = 1;
        Object $type = this.getType();
        result = result * 59 + ($type == null ? 43 : $type.hashCode());
        Object $format = this.getFormat();
        result = result * 59 + ($format == null ? 43 : $format.hashCode());
        Object $description = this.getDescription();
        result = result * 59 + ($description == null ? 43 : $description.hashCode());
        Object $nullable = this.getNullable();
        result = result * 59 + ($nullable == null ? 43 : $nullable.hashCode());
        Object $enumeration = this.getEnumeration();
        result = result * 59 + ($enumeration == null ? 43 : $enumeration.hashCode());
        Object $maxItems = this.getMaxItems();
        result = result * 59 + ($maxItems == null ? 43 : $maxItems.hashCode());
        Object $properties = this.getProperties();
        result = result * 59 + ($properties == null ? 43 : $properties.hashCode());
        Object $required = this.getRequired();
        result = result * 59 + ($required == null ? 43 : $required.hashCode());
        Object $items = this.getItems();
        result = result * 59 + ($items == null ? 43 : $items.hashCode());
        return result;
    }

    public String toString() {
        String var10000 = String.valueOf(this.getType());
        return "GeminiSchema(type=" + var10000 + ", format=" + this.getFormat() + ", description="
                + this.getDescription() + ", nullable=" + this.getNullable() + ", enumeration="
                + String.valueOf(this.getEnumeration()) + ", maxItems=" + this.getMaxItems() + ", properties="
                + String.valueOf(this.getProperties()) + ", required=" + String.valueOf(this.getRequired()) + ", items="
                + String.valueOf(this.getItems()) + ")";
    }

    public static class GeminiSchemaBuilder {
        private GeminiType type;
        private String format;
        private String description;
        private Boolean nullable;
        private List<String> enumeration;
        private String maxItems;
        private Map<String, GeminiSchema> properties;
        private List<String> required;
        private GeminiSchema items;

        GeminiSchemaBuilder() {
        }

        public GeminiSchemaBuilder type(GeminiType type) {
            this.type = type;
            return this;
        }

        public GeminiSchemaBuilder format(String format) {
            this.format = format;
            return this;
        }

        public GeminiSchemaBuilder description(String description) {
            this.description = description;
            return this;
        }

        public GeminiSchemaBuilder nullable(Boolean nullable) {
            this.nullable = nullable;
            return this;
        }

        public GeminiSchemaBuilder enumeration(List<String> enumeration) {
            this.enumeration = enumeration;
            return this;
        }

        public GeminiSchema.GeminiSchemaBuilder maxItems(String maxItems) {
            this.maxItems = maxItems;
            return this;
        }

        public GeminiSchemaBuilder properties(Map<String, GeminiSchema> properties) {
            this.properties = properties;
            return this;
        }

        public GeminiSchemaBuilder required(List<String> required) {
            this.required = required;
            return this;
        }

        public GeminiSchemaBuilder items(GeminiSchema items) {
            this.items = items;
            return this;
        }

        public GeminiSchema build() {
            return new GeminiSchema(this.type, this.format, this.description, this.nullable, this.enumeration,
                    this.maxItems, this.properties, this.required, this.items);
        }

        public String toString() {
            String var10000 = String.valueOf(this.type);
            return "GeminiSchema.GeminiSchemaBuilder(type=" + var10000 + ", format=" + this.format + ", description="
                    + this.description + ", nullable=" + this.nullable + ", enumeration="
                    + String.valueOf(this.enumeration) + ", maxItems=" + this.maxItems + ", properties="
                    + String.valueOf(this.properties) + ", required=" + String.valueOf(this.required) + ", items="
                    + String.valueOf(this.items) + ")";
        }
    }
}
