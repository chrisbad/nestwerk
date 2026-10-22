package club.badernet.nestwerk.web;

import club.badernet.nestwerk.blocker.BlockerBands;
import club.badernet.nestwerk.calendar.TimeGridLayout;

import java.time.LocalDate;
import java.util.List;

/**
 * A day of the time grid with its bands and its columns: one column, or one per person when the grid is split by
 * person.
 */
public record GridDay(LocalDate date, boolean today, boolean weekend, List<BlockerBands.Band> bands,
                      List<Column> columns) {

    /**
     * A column of the day with its timed entries and blocker zones. {@code memberId} is the person of the column,
     * {@code null} for the only column of an unsplit grid and for "Alle".
     */
    public record Column(Long memberId, List<TimeGridLayout.Placed<GridEntry>> entries, List<GridZone> zones,
                         List<GridBlock> blocks, List<OverlapZone> overlaps) {
    }

    /**
     * The time of an event or chore of one person, drawn red while another entry is dragged that must not overlap
     * it. {@code key} identifies the occupant (to skip the dragged entry itself), {@code kind} is {@code event} or
     * {@code chore}, {@code flag} tells that the occupant forbids overlap itself.
     */
    public record OverlapZone(int startMinute, int endMinute, Long memberId, String key, String kind, boolean flag,
                              String label) {
    }

    /**
     * A blocked time drawn behind the entries. Minutes count from midnight, the end is exclusive. In an unsplit
     * column the affected persons stand side by side: {@code stripe} of {@code stripes}.
     */
    public record GridBlock(int startMinute, int endMinute, int stripe, int stripes, String kind, String color,
                            String label, String times) {
    }

    /**
     * A time in which the viewer may not ({@code block}) or should not ({@code warn}) plan the member; shown while
     * dragging. Minutes count from midnight, the end is exclusive.
     */
    public record GridZone(int startMinute, int endMinute, Long memberId, String strength, String label) {
    }
}
