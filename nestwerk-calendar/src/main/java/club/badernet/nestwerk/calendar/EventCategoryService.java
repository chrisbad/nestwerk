package club.badernet.nestwerk.calendar;

import club.badernet.nestwerk.user.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/** Event categories: everyone who sees the calendar may read them, managing needs its own permission. */
@Service
public class EventCategoryService {

    private final EventCategoryRepository categories;
    private final CalendarEventRepository events;
    private final CalendarAccess access;

    public EventCategoryService(EventCategoryRepository categories, CalendarEventRepository events,
                                CalendarAccess access) {
        this.categories = categories;
        this.events = events;
        this.access = access;
    }

    /** All categories sorted by name. */
    @Transactional(readOnly = true)
    public List<EventCategory> list(User viewer) {
        access.require(viewer, CalendarPermission.CALENDAR_VIEW);
        return categories.findAllByOrderByNameAsc();
    }

    @Transactional(readOnly = true)
    public EventCategory get(Long id, User viewer) {
        access.require(viewer, CalendarPermission.CALENDAR_VIEW);
        return categories.findById(id).orElseThrow(CategoryNotFoundException::new);
    }

    /** The category with this name (case-insensitive), e.g. for the CATEGORIES of an imported iCal file. */
    @Transactional(readOnly = true)
    public Optional<EventCategory> findByName(String name, User viewer) {
        access.require(viewer, CalendarPermission.CALENDAR_VIEW);
        return name == null || name.isBlank() ? Optional.empty() : categories.findByNameIgnoreCase(name.trim());
    }

    public boolean canManage(User user) {
        return access.has(user, CalendarPermission.CALENDAR_CATEGORY_MANAGE);
    }

    @Transactional
    public EventCategory create(CategoryInput in, User actor) {
        access.require(actor, CalendarPermission.CALENDAR_CATEGORY_MANAGE);
        String name = requireUniqueName(in.name(), null);
        return categories.save(new EventCategory(name, requireColor(in.color())));
    }

    @Transactional
    public EventCategory update(Long id, CategoryInput in, User actor) {
        access.require(actor, CalendarPermission.CALENDAR_CATEGORY_MANAGE);
        EventCategory category = categories.findById(id).orElseThrow(CategoryNotFoundException::new);
        category.change(requireUniqueName(in.name(), id), requireColor(in.color()));
        return category;
    }

    /** Deletes the category; its events keep existing without a category. */
    @Transactional
    public void delete(Long id, User actor) {
        access.require(actor, CalendarPermission.CALENDAR_CATEGORY_MANAGE);
        EventCategory category = categories.findById(id).orElseThrow(CategoryNotFoundException::new);
        events.clearCategory(id);
        categories.delete(category);
    }

    private String requireUniqueName(String name, Long ownId) {
        String cleaned = name == null ? "" : name.trim();
        if (cleaned.isEmpty() || cleaned.length() > EventCategory.MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("Der Name muss 1–" + EventCategory.MAX_NAME_LENGTH
                    + " Zeichen lang sein.");
        }
        categories.findByNameIgnoreCase(cleaned).filter(other -> !other.getId().equals(ownId)).ifPresent(other -> {
            throw new IllegalArgumentException("Eine Kategorie „" + other.getName() + "“ gibt es schon.");
        });
        return cleaned;
    }

    private static CategoryColor requireColor(CategoryColor color) {
        if (color == null) {
            throw new IllegalArgumentException("Bitte eine Farbe wählen.");
        }
        return color;
    }
}
