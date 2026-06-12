package notification_service.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;

/**
 * Wires the AWS SES v2 client only when aws.ses.enabled=true.
 *
 * Credential resolution order:
 *  1. If aws.access-key / aws.secret-key are non-empty and not placeholder values →
 *     use explicit StaticCredentialsProvider (local dev / CI with explicit keys).
 *  2. Otherwise → use DefaultCredentialsProvider, which checks in order:
 *       a. AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY env vars
 *       b. ~/.aws/credentials file
 *       c. EC2/ECS IAM instance-role metadata service
 *     This is the right choice when running in AWS (ECS, Lambda, EC2 with IAM role).
 */
@Configuration
@ConditionalOnProperty(name = "aws.ses.enabled", havingValue = "true")
public class AwsConfig {

    @Value("${aws.region:us-east-1}")
    private String region;

    @Value("${aws.access-key:}")
    private String accessKey;

    @Value("${aws.secret-key:}")
    private String secretKey;

    @Bean
    public SesV2Client sesV2Client() {
        AwsCredentialsProvider credentialsProvider = isStaticCredential(accessKey) && isStaticCredential(secretKey)
                ? StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey))
                : DefaultCredentialsProvider.create();

        return SesV2Client.builder()
                .region(Region.of(region))
                .credentialsProvider(credentialsProvider)
                .build();
    }

    /** Returns false for blank values and obvious placeholder strings. */
    private boolean isStaticCredential(String value) {
        return value != null && !value.isBlank()
                && !value.startsWith("your-")
                && !value.equals("CHANGE_ME");
    }
}

