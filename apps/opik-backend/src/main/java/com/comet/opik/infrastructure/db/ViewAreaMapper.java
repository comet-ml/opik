package com.comet.opik.infrastructure.db;

import com.comet.opik.api.view.ViewArea;

public class ViewAreaMapper extends AbstractEnumColumnMapper<ViewArea> {
    public ViewAreaMapper() {
        super(ViewArea::fromString, "view_area");
    }
}
