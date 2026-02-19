package com.comet.opik.domain;

import com.comet.opik.api.runner.ConnectRequest;
import com.comet.opik.api.runner.ConnectResponse;
import com.comet.opik.api.runner.CreateJobRequest;
import com.comet.opik.api.runner.LogEntry;
import com.comet.opik.api.runner.PairResponse;
import com.comet.opik.api.runner.Runner;
import com.comet.opik.api.runner.RunnerJob;
import com.google.inject.ImplementedBy;
import lombok.NonNull;

import java.util.List;

@ImplementedBy(RunnerServiceImpl.class)
public interface RunnerService {

    PairResponse generatePairingCode(@NonNull String workspaceId);

    ConnectResponse connect(@NonNull ConnectRequest request);

    List<Runner> listRunners(@NonNull String workspaceId);

    Runner getRunner(@NonNull String runnerId, @NonNull String workspaceId);

    RunnerJob createJob(@NonNull CreateJobRequest request, @NonNull String workspaceId);

    List<RunnerJob> listJobs(@NonNull String runnerId, String project);

    RunnerJob getJob(@NonNull String jobId);

    List<LogEntry> getJobLogs(@NonNull String jobId, int offset);
}
