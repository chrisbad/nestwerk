package club.badernet.nestwerk.role;

import club.badernet.nestwerk.security.PermissionCatalog;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name = "roles")
public class Role {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 255)
    private String description;

    @Column(name = "system_role", nullable = false)
    private boolean systemRole;

    @Column(name = "all_permissions", nullable = false)
    private boolean allPermissions;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "role_permissions", joinColumns = @JoinColumn(name = "role_id"))
    @Column(name = "permission", nullable = false, length = 100)
    private Set<String> permissions = new HashSet<>();

    protected Role() {
    }

    public Role(String name, String description, Set<String> permissions) {
        this.name = name;
        this.description = description;
        this.permissions = new HashSet<>(permissions);
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public boolean isSystemRole() {
        return systemRole;
    }

    public boolean isAllPermissions() {
        return allPermissions;
    }

    /** Gespeicherte Rechte, die im Katalog existieren (unbekannte Namen werden ignoriert). */
    public Set<String> getPermissions() {
        Set<String> known = new LinkedHashSet<>();
        for (String p : permissions) {
            if (PermissionCatalog.find(p).isPresent()) {
                known.add(p);
            }
        }
        return known;
    }

    public Set<String> effectivePermissionNames() {
        return allPermissions ? PermissionCatalog.names() : getPermissions();
    }

    public void rename(String name) {
        this.name = name;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setPermissions(Set<String> permissions) {
        this.permissions.clear();
        this.permissions.addAll(permissions);
    }
}
