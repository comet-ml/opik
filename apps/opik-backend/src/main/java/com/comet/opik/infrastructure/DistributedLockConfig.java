package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class DistributedLockConfig {

    @Valid @JsonProperty
    @NotNull private int lockTimeoutMS; // lease time in milliseconds

    /**
     *
     * This value has to be considerably higher than the lockTimeoutMS value, as it has to guarantee that the last
     * thread to join the queue to acquire the lock will have enough time to execute the action. Then, the lock will be deleted from redis after the @ttlInSeconds.
     * <br>
     * This is needed as Redisson by default doesn't delete the lock from redis after the lease time expires, it just releases the lock. The expiration time will be reset every time the lock is acquired.
     * */
    @Valid @JsonProperty
    @NotNull private int ttlInSeconds; // time to live in seconds

}
