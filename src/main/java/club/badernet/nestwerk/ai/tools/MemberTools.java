package club.badernet.nestwerk.ai.tools;

import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import club.badernet.nestwerk.security.PermissionCatalog;
import io.modelcontextprotocol.common.McpTransportContext;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Wer bin ich, und wer gehört zur Familie. */
@Component
public class MemberTools {

    public record Me(Long userId, String username, Long memberId, String memberName, boolean administrator,
                     Set<String> permissions) {
    }

    public record Member(Long id, String name, boolean hasAccount) {
    }

    private final Tools tools;
    private final FamilyMemberRepository members;
    private final PermissionCatalog permissions;

    public MemberTools(Tools tools, FamilyMemberRepository members, PermissionCatalog permissions) {
        this.tools = tools;
        this.members = members;
        this.permissions = permissions;
    }

    @McpTool(name = "whoami", description = "Zeigt, in wessen Namen die KI handelt: Benutzer, verknüpftes "
            + "Familienmitglied und Rechte.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false,
                    openWorldHint = false))
    public Me whoami(McpTransportContext context) {
        return tools.read(context, user -> {
            FamilyMember member = members.findByUserId(user.getId()).orElse(null);
            return new Me(user.getId(), user.getUsername(), member == null ? null : member.getId(),
                    member == null ? null : member.getName(), user.isAdministrator(),
                    new TreeSet<>(permissions.effective(user)));
        });
    }

    @McpTool(name = "list_members", description = "Listet alle Familienmitglieder mit ID. Die IDs werden für "
            + "Termin-Teilnehmer, ToDo-Zuständige und Helfer bei Hausarbeiten gebraucht.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public List<Member> listMembers(McpTransportContext context) {
        return tools.read(context, user -> members.findAllByOrderByNameAsc().stream()
                .map(m -> new Member(m.getId(), m.getName(), m.getUserId() != null)).toList());
    }
}
