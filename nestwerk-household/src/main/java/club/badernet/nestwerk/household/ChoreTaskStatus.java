package club.badernet.nestwerk.household;

public enum ChoreTaskStatus {
    OPEN,
    DONE,
    /** Dropped for this one date; the row stays so that planning does not create the task again. */
    CANCELLED
}
