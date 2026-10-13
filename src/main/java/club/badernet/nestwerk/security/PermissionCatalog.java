package club.badernet.nestwerk.security;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class PermissionCatalog {

    private static final List<Permission> ALL = collect();

    private PermissionCatalog() {
    }

    private static List<Permission> collect() {
        List<Permission> all = new ArrayList<>();
        all.addAll(Arrays.asList(AdminPermission.values()));
        return List.copyOf(all);
    }

    public static List<Permission> all() {
        return ALL;
    }

    public static Set<String> names() {
        Set<String> names = new LinkedHashSet<>();
        ALL.forEach(p -> names.add(p.name()));
        return names;
    }

    public static Optional<Permission> find(String name) {
        return ALL.stream().filter(p -> p.name().equals(name)).findFirst();
    }

    public static Map<String, List<Permission>> byModule() {
        Map<String, List<Permission>> grouped = new LinkedHashMap<>();
        for (Permission p : ALL) {
            grouped.computeIfAbsent(p.module(), k -> new ArrayList<>()).add(p);
        }
        return grouped;
    }
}
