package club.badernet.nestwerk.web;

import java.time.LocalDate;

/**
 * An open chore of a day without a planned time or without a responsible person; it appears in the all-day row.
 * {@code memberId} is {@code null} for a chore nobody is responsible for.
 */
public record UntimedChore(Long id, String title, LocalDate day, String color, Long memberId) {
}
