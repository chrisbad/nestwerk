package club.badernet.nestwerk.web;

import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;
import org.springframework.stereotype.Component;

/**
 * Wandelt Markdown aus Beschreibungen und Kommentaren in HTML um. Roh-HTML wird maskiert und das
 * Ergebnis zusätzlich mit einer Positivliste bereinigt, damit niemand Skripte einschleusen kann.
 */
@Component
public class MarkdownRenderer {

    private static final PolicyFactory POLICY = new HtmlPolicyBuilder()
            .allowElements("p", "br", "strong", "em", "code", "pre", "blockquote", "ul", "ol", "li",
                    "h1", "h2", "h3", "h4", "h5", "h6", "hr", "a")
            .allowUrlProtocols("http", "https", "mailto")
            .allowAttributes("href").onElements("a")
            .requireRelsOnLinks("nofollow", "noopener", "noreferrer")
            .toFactory();

    private final Parser parser = Parser.builder().build();
    private final HtmlRenderer renderer = HtmlRenderer.builder().escapeHtml(true).sanitizeUrls(true).build();

    public String render(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        return POLICY.sanitize(renderer.render(parser.parse(markdown)));
    }
}
