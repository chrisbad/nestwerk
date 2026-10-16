package club.badernet.nestwerk.kitchen;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PortionAdvisorTest {

    @Test
    void suggestsPortionFromGoalShareAndKcal() {
        assertThat(PortionAdvisor.suggest(2000, 30, 600.0)).isEqualTo(1.0);
        assertThat(PortionAdvisor.suggest(2500, 30, 600.0)).isEqualTo(1.25);
        assertThat(PortionAdvisor.suggest(1400, 30, 600.0)).isEqualTo(0.75);
    }

    @Test
    void staysWithinBoundsAndFallsBackToOne() {
        assertThat(PortionAdvisor.suggest(4000, 35, 200.0)).isEqualTo(3.0);
        assertThat(PortionAdvisor.suggest(800, 10, 900.0)).isEqualTo(0.5);
        assertThat(PortionAdvisor.suggest(null, 30, 600.0)).isEqualTo(1.0);
        assertThat(PortionAdvisor.suggest(2000, 30, null)).isEqualTo(1.0);
        assertThat(PortionAdvisor.suggest(2000, 0, 600.0)).isEqualTo(1.0);
    }
}
