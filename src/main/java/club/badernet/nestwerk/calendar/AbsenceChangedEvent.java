package club.badernet.nestwerk.calendar;

/** Ein Abwesenheitstermin wurde angelegt, geändert oder gelöscht (für die Neuverteilung im Putzplan). */
public record AbsenceChangedEvent(Long eventId) {
}
