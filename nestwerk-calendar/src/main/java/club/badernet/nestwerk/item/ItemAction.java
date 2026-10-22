package club.badernet.nestwerk.item;

/** What the viewer may do with an item right on the calendar; the domain services decide per item. */
public enum ItemAction {
    EDIT,
    DELETE,
    COMPLETE,
    MOVE,
    SKIP,
    UNDO
}
