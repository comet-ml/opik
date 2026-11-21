package com.comet.opik.domain;

import com.clickhouse.client.api.metadata.NoSuchColumnException;
import com.clickhouse.client.api.metadata.TableSchema;
import com.clickhouse.client.api.query.Records;
import com.clickhouse.data.ClickHouseColumn;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FreeFormSqlQueryDAOImplTest {

    @Test
    void rejectsExtraColumnsEvenWhenResultIsPresent() {
        var records = mock(Records.class);
        when(records.getSchema()).thenReturn(new TableSchema(List.of(
                ClickHouseColumn.of("result", "String"), ClickHouseColumn.of("extra", "Int32"))));

        assertThatThrownBy(() -> FreeFormSqlQueryDAOImpl.readResult(records))
                .isInstanceOf(NoSuchColumnException.class);
    }

    @Test
    void rejectsAColumnNamedSomethingOtherThanResult() {
        var records = mock(Records.class);
        when(records.getSchema()).thenReturn(new TableSchema(List.of(ClickHouseColumn.of("other", "String"))));

        assertThatThrownBy(() -> FreeFormSqlQueryDAOImpl.readResult(records))
                .isInstanceOf(NoSuchColumnException.class);
    }
}
