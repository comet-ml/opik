package com.comet.opik.domain.topics;

/** What a nightly update's claim found ({@link TopicRunStore#claimNightly}). */
enum NightClaim {
    CLAIMED,
    /** Another pod claimed it, or it ended. */
    LOST,
    /** The project has no list any more: the run ended without a save. */
    NO_LIST,
    /** The project's list already covers the night: the run ended without a save. */
    ALREADY_SAVED
}
