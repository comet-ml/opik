package com.comet.opik.domain.topics;

import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Step c of the nightly update: a topic's (or the known-Other pool's) prototypes move to its members of tonight's
 * window. k = min({@value #MAX_PROTOTYPES}, max(floor, members / {@value #MEMBERS_PER_PROTOTYPE})), floor
 * {@value #FLOOR} when the set has at least {@value #FLOOR_MIN_MEMBERS} members, else 1; at most the number of unique
 * texts. When the old prototype count equals k, one Lloyd run starts from them (warm start), else k-means++ with
 * restarts; seed 0. As the stand's {@code recentre} with {@code proto_floor=2}.
 */
@UtilityClass
public class Recentre {

    static final int MEMBERS_PER_PROTOTYPE = 150;
    static final int MAX_PROTOTYPES = 10;
    static final int FLOOR = 2;
    static final int FLOOR_MIN_MEMBERS = 50;

    static int k(int members, int uniqueTexts) {
        int floor = members >= FLOOR_MIN_MEMBERS ? FLOOR : 1;
        return Math.min(Math.min(MAX_PROTOTYPES, Math.max(floor, members / MEMBERS_PER_PROTOTYPE)), uniqueTexts);
    }

    /**
     * @param members the set's traces in the window, in window order
     * @param old     the set's prototypes before tonight
     * @param topic   the topic's id, null for the known-Other pool
     */
    public static List<float[]> run(@NonNull NightWindow window, @NonNull int[] members, @NonNull List<float[]> old,
            UUID topic, @NonNull NightClusterer clusterer) {
        int d = window.dimensions();
        var merged = IntentMerger.merge(window.texts(members), window.rows(members), d);
        int k = k(members.length, merged.size());
        float[] init = null;
        if (old.size() == k) {
            init = new float[k * d];
            for (int j = 0; j < k; j++) {
                System.arraycopy(old.get(j), 0, init, j * d, d);
            }
        }
        var result = clusterer.fit(NightClusterer.Request.builder()
                .kind(topic == null ? NightClusterer.Kind.RECENTRE_OTHER : NightClusterer.Kind.RECENTRE_TOPIC)
                .topic(topic)
                .points(merged)
                .k(k)
                .seed(0)
                .init(init)
                .build());
        return rows(result.centres(), d);
    }

    static List<float[]> rows(float[] centres, int d) {
        var out = new ArrayList<float[]>(centres.length / d);
        for (int j = 0; j < centres.length / d; j++) {
            var row = new float[d];
            System.arraycopy(centres, j * d, row, 0, d);
            out.add(row);
        }
        return out;
    }
}
