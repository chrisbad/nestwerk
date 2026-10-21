package club.badernet.nestwerk.web;

/** A chore the viewer can plan or book from the calendar, with the minutes of its three effort levels. */
public record QuickChore(Long id, String title, int minutesEasy, int minutesMedium, int minutesHard) {
}
