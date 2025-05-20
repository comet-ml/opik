package com.comet.opik.utils.csv;

import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItemSource;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.experimental.UtilityClass;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@UtilityClass
public class DatasetCsvParser {

    public List<DatasetItem> parse(InputStream inputStream) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
        String headerLine = reader.readLine();
        if (headerLine == null) {
            return List.of();
        }
        String[] headers = headerLine.split(",");
        List<DatasetItem> items = new ArrayList<>();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            String[] values = line.split(",");
            Map<String, JsonNode> data = new HashMap<>();
            for (int i = 0; i < headers.length && i < values.length; i++) {
                data.put(headers[i].trim(), JsonUtils.MAPPER.getNodeFactory().textNode(values[i]));
            }
            DatasetItem item = DatasetItem.builder()
                    .source(DatasetItemSource.MANUAL)
                    .data(data)
                    .build();
            items.add(item);
        }
        return items;
    }
}
