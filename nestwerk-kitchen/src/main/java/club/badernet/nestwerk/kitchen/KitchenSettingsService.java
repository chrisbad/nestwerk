package club.badernet.nestwerk.kitchen;

import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import club.badernet.nestwerk.user.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/** Mahlzeiten (Slots) und Kalorienziele pflegen. */
@Service
public class KitchenSettingsService {

    private final MealSlotRepository slots;
    private final FamilyMemberRepository members;
    private final KitchenAccess access;

    public KitchenSettingsService(MealSlotRepository slots, FamilyMemberRepository members, KitchenAccess access) {
        this.slots = slots;
        this.members = members;
        this.access = access;
    }

    @Transactional
    public void setGoals(Map<Long, Integer> goals, User actor) {
        access.require(actor, KitchenPermission.KITCHEN_SETTINGS);
        for (FamilyMember m : members.findAll()) {
            if (!goals.containsKey(m.getId())) {
                continue;
            }
            Integer goal = goals.get(m.getId());
            if (goal != null && (goal < 500 || goal > 10000)) {
                throw new IllegalArgumentException("Kalorienziele liegen zwischen 500 und 10.000 kcal.");
            }
            m.setKcalGoal(goal);
        }
    }

    @Transactional
    public MealSlot createSlot(String name, int share, boolean autoFill, User actor) {
        access.require(actor, KitchenPermission.KITCHEN_SETTINGS);
        List<MealSlot> all = slots.findAllByOrderBySortOrderAsc();
        int order = all.isEmpty() ? 10 : all.getLast().getSortOrder() + 10;
        return slots.save(new MealSlot(name(name), order, share(share), autoFill));
    }

    @Transactional
    public void updateSlot(Long id, String name, int share, boolean autoFill, User actor) {
        access.require(actor, KitchenPermission.KITCHEN_SETTINGS);
        MealSlot slot = slots.findById(id).orElseThrow(() -> new NoSuchElementException("Mahlzeit nicht gefunden"));
        slot.change(name(name), slot.getSortOrder(), share(share), autoFill);
    }

    /** Löscht die Mahlzeit samt geplanter Einträge. */
    @Transactional
    public void deleteSlot(Long id, User actor) {
        access.require(actor, KitchenPermission.KITCHEN_SETTINGS);
        if (!slots.existsById(id)) {
            throw new IllegalArgumentException("Die Mahlzeit gibt es nicht.");
        }
        if (slots.count() <= 1) {
            throw new IllegalArgumentException("Mindestens eine Mahlzeit muss bleiben.");
        }
        slots.deleteById(id);
    }

    private static String name(String name) {
        String n = name == null ? "" : name.strip();
        if (n.isEmpty() || n.length() > 50) {
            throw new IllegalArgumentException("Der Name der Mahlzeit fehlt oder ist zu lang.");
        }
        return n;
    }

    private static int share(int share) {
        if (share < 0 || share > 100) {
            throw new IllegalArgumentException("Der Anteil liegt zwischen 0 und 100 %.");
        }
        return share;
    }
}
