package com.statement.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.textract.TextractClient;

/**
 * The three AWS clients the app needs, each a shared Spring singleton.
 *
 * This is the AWS analogue of the subagent demo's {@code AnthropicConfig}: one
 * place that builds the clients, with no credentials in code. {@link
 * DefaultCredentialsProvider} walks the standard chain (environment, system
 * properties, the shared config/credentials files, SSO/login sessions), so
 * running the app with {@code AWS_PROFILE=ronny} makes it use the profile we
 * authenticated with via {@code aws login}. Nothing secret is hard-coded.
 *
 * All clients are pinned to {@code aws.region} (us-east-2, the project Region),
 * because the account rules require every resource to live there.
 */
@Configuration
public class AwsConfig {

    private final Region region;
    private final DefaultCredentialsProvider credentials = DefaultCredentialsProvider.create();

    public AwsConfig(@Value("${aws.region}") String region) {
        this.region = Region.of(region);
    }

    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .region(region)
                .credentialsProvider(credentials)
                .build();
    }

    @Bean
    public TextractClient textractClient() {
        return TextractClient.builder()
                .region(region)
                .credentialsProvider(credentials)
                .build();
    }

    @Bean
    public BedrockRuntimeClient bedrockRuntimeClient() {
        return BedrockRuntimeClient.builder()
                .region(region)
                .credentialsProvider(credentials)
                .build();
    }
}
