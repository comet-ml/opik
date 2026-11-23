package com.comet.opik.infrastructure.db;

import com.comet.opik.api.view.ViewType;

public class ViewTypeMapper extends AbstractEnumColumnMapper<ViewType> {
    public ViewTypeMapper() {
        super(ViewType::fromString, "view_type");
    }
}
