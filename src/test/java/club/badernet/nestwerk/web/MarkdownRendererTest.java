package club.badernet.nestwerk.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownRendererTest {

    private final MarkdownRenderer renderer = new MarkdownRenderer();

    @Test
    void rendersBasicMarkdown() {
        String html = renderer.render("**Wichtig**\n\n- Fliesen\n- Fugen");

        assertThat(html).contains("<strong>Wichtig</strong>").contains("<li>Fliesen</li>");
    }

    @Test
    void removesScriptsAndRawHtml() {
        String html = renderer.render("Hallo <script>alert(1)</script> <img src=x onerror=alert(2)>");

        assertThat(html).doesNotContain("<script").doesNotContain("<img").contains("&lt;script&gt;");
    }

    @Test
    void allowsOnlySafeLinks() {
        String html = renderer.render("[gut](https://example.org) [böse](javascript:alert(1))");

        assertThat(html).contains("href=\"https://example.org\"").contains("nofollow").contains("noopener")
                .doesNotContain("javascript:");
    }

    @Test
    void emptyTextIsEmpty() {
        assertThat(renderer.render(null)).isEmpty();
        assertThat(renderer.render("  ")).isEmpty();
    }
}
