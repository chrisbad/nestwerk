package club.badernet.nestwerk.notification;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/** E-Mail-Versand; eingerichtet ist er, wenn {@code MAIL_HOST} gesetzt ist. */
@Component
public class MailDelivery {

    private final ObjectProvider<JavaMailSender> sender;
    private final String host;
    private final String from;
    private final String baseUrl;

    public MailDelivery(ObjectProvider<JavaMailSender> sender, @Value("${spring.mail.host:}") String host,
                        @Value("${nestwerk.mail.from:}") String from,
                        @Value("${nestwerk.base-url:http://localhost:8080}") String baseUrl) {
        this.sender = sender;
        this.host = host;
        this.from = from;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    public boolean configured() {
        return host != null && !host.isBlank() && sender.getIfAvailable() != null;
    }

    public String absolute(String link) {
        return link == null ? baseUrl : link.startsWith("/") ? baseUrl + link : link;
    }

    /** Schickt eine einfache HTML-Mail mit Textalternative und Link. */
    public void send(String to, String subject, String text, String link) {
        JavaMailSender mail = sender.getIfAvailable();
        if (!configured() || mail == null) {
            throw new IllegalStateException("E-Mail ist nicht eingerichtet.");
        }
        String url = absolute(link);
        try {
            MimeMessage message = mail.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setTo(to);
            if (from != null && !from.isBlank()) {
                helper.setFrom(from);
            }
            helper.setSubject(subject);
            String html = "<div style=\"font-family:sans-serif;max-width:32rem\">"
                    + "<p style=\"font-size:1.1rem;font-weight:bold\">" + HtmlUtils.htmlEscape(subject) + "</p>"
                    + "<p>" + HtmlUtils.htmlEscape(text == null ? "" : text) + "</p>"
                    + "<p><a href=\"" + HtmlUtils.htmlEscape(url) + "\">In Nestwerk öffnen</a></p>"
                    + "<p style=\"color:#777;font-size:0.85rem\">Welche Mails du bekommst, stellst du in Nestwerk "
                    + "unter „Benachrichtigungen“ ein.</p></div>";
            helper.setText((text == null ? "" : text) + "\n\n" + url, html);
            mail.send(message);
        } catch (MessagingException | MailException e) {
            throw new IllegalStateException("Mailversand fehlgeschlagen: " + e.getMessage(), e);
        }
    }
}
