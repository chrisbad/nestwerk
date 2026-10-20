package club.badernet.nestwerk.calendar;

import club.badernet.nestwerk.security.Permission;

public enum CalendarPermission implements Permission {
    CALENDAR_VIEW,
    CALENDAR_EVENT_CREATE,
    CALENDAR_EVENT_EDIT_OWN,
    CALENDAR_EVENT_EDIT_ALL,
    CALENDAR_EVENT_DELETE_OWN,
    CALENDAR_EVENT_DELETE_ALL,
    CALENDAR_PRIVATE_CREATE,
    CALENDAR_CATEGORY_MANAGE
}
