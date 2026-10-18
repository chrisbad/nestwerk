package club.badernet.nestwerk.backup;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RetentionAndScheduleTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** Eine Sicherung pro Tag, rückwärts ab {@code last}. */
    private static List<Retention.Item> daily(LocalDate last, int days) {
        List<Retention.Item> items = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            ZonedDateTime t = last.minusDays(i).atTime(2, 30).atZone(BERLIN);
            // Änderungszeit absichtlich falsch: maßgeblich ist der Zeitstempel im Namen
            items.add(new Retention.Item("nestwerk-" + t.format(STAMP) + ".zip", Instant.EPOCH));
        }
        return items;
    }

    @Test
    void keepsLastNAndNeverTouchesUploads() {
        List<Retention.Item> items = new ArrayList<>(daily(LocalDate.of(2026, 10, 16), 10));
        items.add(new Retention.Item("nestwerk-hochgeladen-20200101-000000.zip", Instant.EPOCH));

        Set<String> delete = Retention.toDelete(items, new BackupSettings.RetentionRule(3, 0, 0), BERLIN);

        assertThat(delete).hasSize(7).doesNotContain("nestwerk-hochgeladen-20200101-000000.zip")
                .doesNotContain("nestwerk-20261016-023000.zip", "nestwerk-20261015-023000.zip",
                        "nestwerk-20261014-023000.zip");
    }

    @Test
    void keepsNewestPerWeekAndMonth() {
        List<Retention.Item> items = daily(LocalDate.of(2026, 10, 16), 120);

        Set<String> delete = Retention.toDelete(items, new BackupSettings.RetentionRule(2, 3, 4), BERLIN);
        List<String> kept = items.stream().map(Retention.Item::name).filter(n -> !delete.contains(n)).toList();

        // 2 neueste; Wochen: KW42 (16.10.), KW41 (11.10.), KW40 (4.10.); Monate: Okt, Sep (30.9.), Aug (31.8.), Jul
        assertThat(kept).containsExactlyInAnyOrder("nestwerk-20261016-023000.zip", "nestwerk-20261015-023000.zip",
                "nestwerk-20261011-023000.zip", "nestwerk-20261004-023000.zip", "nestwerk-20260930-023000.zip",
                "nestwerk-20260831-023000.zip", "nestwerk-20260731-023000.zip");
    }

    @Test
    void encryptedCopiesFollowTheSameRule() {
        List<Retention.Item> items = daily(LocalDate.of(2026, 10, 16), 5).stream()
                .map(i -> new Retention.Item(i.name() + ".enc", i.time())).toList();

        assertThat(Retention.toDelete(items, new BackupSettings.RetentionRule(4, 0, 0), BERLIN))
                .containsExactly("nestwerk-20261012-023000.zip.enc");
    }

    @Test
    void computesDailyAndWeeklySlots() {
        BackupSettings.Schedule daily = new BackupSettings.Schedule(true, BackupSettings.Frequency.DAILY,
                DayOfWeek.SUNDAY, LocalTime.of(2, 30));
        ZonedDateTime before = ZonedDateTime.of(2026, 10, 16, 1, 0, 0, 0, BERLIN);
        ZonedDateTime after = ZonedDateTime.of(2026, 10, 16, 2, 30, 0, 0, BERLIN);

        assertThat(BackupScheduler.lastSlot(daily, before)).isEqualTo(after.minusDays(1));
        assertThat(BackupScheduler.lastSlot(daily, after)).isEqualTo(after);
        assertThat(BackupScheduler.nextSlot(daily, before)).isEqualTo(after);

        BackupSettings.Schedule weekly = new BackupSettings.Schedule(true, BackupSettings.Frequency.WEEKLY,
                DayOfWeek.SUNDAY, LocalTime.of(3, 0));
        // Freitag, 16.10.2026: letzter Sonntag war der 11.10.
        assertThat(BackupScheduler.lastSlot(weekly, after))
                .isEqualTo(ZonedDateTime.of(2026, 10, 11, 3, 0, 0, 0, BERLIN));
        assertThat(BackupScheduler.nextSlot(weekly, after))
                .isEqualTo(ZonedDateTime.of(2026, 10, 18, 3, 0, 0, 0, BERLIN));
        ZonedDateTime sundayEarly = ZonedDateTime.of(2026, 10, 18, 2, 0, 0, 0, BERLIN);
        assertThat(BackupScheduler.lastSlot(weekly, sundayEarly))
                .isEqualTo(ZonedDateTime.of(2026, 10, 11, 3, 0, 0, 0, BERLIN));
    }
}
