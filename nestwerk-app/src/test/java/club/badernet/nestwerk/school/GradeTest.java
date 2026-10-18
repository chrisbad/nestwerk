package club.badernet.nestwerk.school;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GradeTest {

    @Test
    void parsesPlainGradesAndTrends() {
        assertThat(Grade.parse("2")).isEqualTo(20);
        assertThat(Grade.parse("2+")).isEqualTo(17);
        assertThat(Grade.parse("2-")).isEqualTo(23);
        assertThat(Grade.parse(" 3 + ")).isEqualTo(27);
        assertThat(Grade.parse("1+")).as("besser als 1 gibt es nicht").isEqualTo(10);
        assertThat(Grade.parse("6-")).as("schlechter als 6 gibt es nicht").isEqualTo(60);
        assertThat(Grade.parse("4−")).as("typografisches Minus").isEqualTo(43);
    }

    @Test
    void parsesDecimals() {
        assertThat(Grade.parse("2,5")).isEqualTo(25);
        assertThat(Grade.parse("2.3")).isEqualTo(23);
        assertThat(Grade.parse("1,0")).isEqualTo(10);
        assertThat(Grade.parse("6,0")).isEqualTo(60);
    }

    @Test
    void rejectsInvalidInput() {
        for (String bad : new String[] {null, "", "0", "7", "7,0", "6,5", "0,9", "abc", "2++", "2,55", "-"}) {
            assertThatThrownBy(() -> Grade.parse(bad)).as(String.valueOf(bad))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void labelsRoundTripForTrendsAndDecimals() {
        assertThat(Grade.label(10)).isEqualTo("1");
        assertThat(Grade.label(13)).isEqualTo("1-");
        assertThat(Grade.label(17)).isEqualTo("2+");
        assertThat(Grade.label(20)).isEqualTo("2");
        assertThat(Grade.label(23)).isEqualTo("2-");
        assertThat(Grade.label(27)).isEqualTo("3+");
        assertThat(Grade.label(57)).isEqualTo("6+");
        assertThat(Grade.label(60)).isEqualTo("6");
        assertThat(Grade.label(25)).isEqualTo("2,5");
        assertThat(Grade.label(14)).isEqualTo("1,4");
        for (int tenths = Grade.MIN; tenths <= Grade.MAX; tenths++) {
            assertThat(Grade.parse(Grade.label(tenths))).as("Zehntel " + tenths).isEqualTo(tenths);
        }
        assertThat(Grade.format(2.333)).isEqualTo("2,33");
    }

    private static SchoolGrade grade(int tenths, int weight) {
        return new SchoolGrade(1L, 1L, 1, tenths, weight, GradeKind.WRITTEN, LocalDate.of(2026, 9, 1), null);
    }

    @Test
    void averageIsWeighted() {
        assertThat(GradeService.average(List.of())).isNull();
        assertThat(GradeService.average(List.of(grade(20, 100)))).isEqualTo(2.0);
        assertThat(GradeService.average(List.of(grade(20, 100), grade(30, 100)))).isEqualTo(2.5);
        assertThat(GradeService.average(List.of(grade(20, 200), grade(50, 100)))).isEqualTo(3.0);
        assertThat(GradeService.average(List.of(grade(10, 100), grade(20, 100), grade(20, 100)))).isEqualTo(1.67);
        assertThat(GradeService.average(List.of(grade(20, 50), grade(40, 100)))).isEqualTo(3.33);
    }

    @Test
    void trendShowsTheRunningAverage() {
        assertThat(GradeService.trend(List.of(grade(20, 100), grade(40, 100), grade(30, 100))))
                .containsExactly(2.0, 3.0, 3.0);
        assertThat(GradeService.trend(List.of())).isEmpty();
    }
}
