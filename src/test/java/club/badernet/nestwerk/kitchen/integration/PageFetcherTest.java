package club.badernet.nestwerk.kitchen.integration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageFetcherTest {

    final PageFetcher fetcher = new PageFetcher(false);

    @Test
    void rejectsInternalAndOddAddresses() {
        assertThatThrownBy(() -> fetcher.fetch("http://127.0.0.1/rezept")).hasMessageContaining("Interne Adressen");
        assertThatThrownBy(() -> fetcher.fetch("ftp://example.org/x")).hasMessageContaining("https://");
        assertThatThrownBy(() -> fetcher.fetch("https://user:pw@example.org/")).hasMessageContaining("ungültig");
        assertThatThrownBy(() -> fetcher.fetch("https://example.org:8443/")).hasMessageContaining("Port");
    }
}
