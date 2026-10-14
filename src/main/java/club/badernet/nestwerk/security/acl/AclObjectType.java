package club.badernet.nestwerk.security.acl;

import java.util.List;
import java.util.Set;

/** Ein Objekttyp mit seinen Aktionen, den dafür sinnvollen Beziehungen und gültigen Zellen. */
public record AclObjectType(String key, String label, List<AclAction> actions,
                            List<AclRelation> relations, Set<String> cells) {

    public boolean allows(String action, String relation) {
        return cells.contains(action + ":" + relation);
    }
}
