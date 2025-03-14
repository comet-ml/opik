package com.comet.opik.domain.aws;

import com.comet.opik.infrastructure.OpikConfiguration;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.ContainerCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

@UtilityClass
public class AWSUtils {
    private static boolean isEKSPod;

    public static void setConfig(@NonNull OpikConfiguration config) {
        isEKSPod = config.getS3Config().isEKSPod();
    }

    public static AwsCredentialsProvider getAWSCredentials(String awsKey, String awsSecret) {
        if (isEKSPod) {
            return ContainerCredentialsProvider.builder().build();
        }

        return StaticCredentialsProvider.create(AwsBasicCredentials.create(awsKey, awsSecret));
    }
}
