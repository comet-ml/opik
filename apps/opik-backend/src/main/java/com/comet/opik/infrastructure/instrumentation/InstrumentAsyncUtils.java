package com.comet.opik.infrastructure.instrumentation;

import com.newrelic.api.agent.DatastoreParameters;
import com.newrelic.api.agent.NewRelic;
import com.newrelic.api.agent.Segment;
import lombok.extern.slf4j.Slf4j;
import reactor.core.scheduler.Schedulers;

import java.lang.reflect.Method;

@Slf4j
public class InstrumentAsyncUtils {


    public static Segment startSegment(String segmentName, String product, String operationName) {

        Segment segment = NewRelic.getAgent().getTransaction().startSegment(segmentName);

        segment.reportAsExternal(DatastoreParameters
                .product(product)
                .collection(null)
                .operation(operationName)
                .build());

        return segment;
    }

    public static void endSegment(Segment segment) {
        if (segment != null) {
            // Fire and forget logic
            Schedulers.boundedElastic().schedule(() -> {
                try {
                    // End the segment
                    Method endMethod = segment.getClass().getMethod("end");
                    endMethod.invoke(segment);
                } catch (Exception e) {
                    log.warn("Failed to end segment", e);
                }
            });
        }
    }
}
