package notification_service.provider.email;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.*;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "aws.ses.enabled", havingValue = "true")
public class AwsSesProvider implements EmailProvider {

    private final SesV2Client sesV2Client;

    @Value("${aws.ses.from-email}")
    private String fromEmail;

    @Override
    public void send(String to, String subject, String body) {
        SendEmailRequest request = SendEmailRequest.builder()
                .fromEmailAddress(fromEmail)
                .destination(Destination.builder()
                        .toAddresses(to)
                        .build())
                .content(EmailContent.builder()
                        .simple(Message.builder()
                                .subject(Content.builder()
                                        .data(subject)
                                        .charset("UTF-8")
                                        .build())
                                .body(Body.builder()
                                        .html(Content.builder()
                                                .data(body)
                                                .charset("UTF-8")
                                                .build())
                                        .build())
                                .build())
                        .build())
                .build();

        sesV2Client.sendEmail(request);
        log.info("Email sent via AWS SES | to=[{}] subject=[{}]", to, subject);
    }

    @Override
    public String getProviderName() {
        return "AWS_SES";
    }
}
