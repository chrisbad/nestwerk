package club.badernet.nestwerk.web;

import club.badernet.nestwerk.blocker.BlockerBands;
import club.badernet.nestwerk.calendar.TimeGridLayout;

import java.time.LocalDate;
import java.util.List;

/** A day column of the time grid with its timed entries, bands and blocker zones. */
public record GridDay(LocalDate date, boolean today, boolean weekend, List<TimeGridLayout.Placed<GridEntry>> entries,
                      List<BlockerBands.Band> bands, List<GridZone> zones) {

    /**
     * A time in which the viewer may not ({@code block}) or should not ({@code warn}) plan the member; shown while
     * dragging. Minutes count from midnight, the end is exclusive.
     */
    public record GridZone(int startMinute, int endMinute, Long memberId, String strength, String label) {
    }
}
