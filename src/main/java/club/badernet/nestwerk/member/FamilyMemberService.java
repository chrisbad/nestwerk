package club.badernet.nestwerk.member;

import club.badernet.nestwerk.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class FamilyMemberService {

    private static final Pattern COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");

    private final FamilyMemberRepository members;
    private final UserRepository users;

    public FamilyMemberService(FamilyMemberRepository members, UserRepository users) {
        this.members = members;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public List<FamilyMember> findAll() {
        return members.findAllByOrderByNameAsc();
    }

    @Transactional(readOnly = true)
    public Optional<FamilyMember> findByUserId(Long userId) {
        return members.findByUserId(userId);
    }

    @Transactional
    public FamilyMember create(String name, String color, Long userId) {
        String cleanName = requireName(name);
        requireColor(color);
        requireLinkable(userId, null);
        return members.save(new FamilyMember(cleanName, color, userId));
    }

    @Transactional
    public void update(Long id, String name, String color, Long userId) {
        FamilyMember member = members.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Mitglied nicht gefunden"));
        String cleanName = requireName(name);
        requireColor(color);
        requireLinkable(userId, id);
        member.change(cleanName, color, userId);
    }

    @Transactional
    public void delete(Long id) {
        if (!members.existsById(id)) {
            throw new IllegalArgumentException("Mitglied nicht gefunden");
        }
        members.deleteById(id);
    }

    private String requireName(String name) {
        String cleaned = name == null ? "" : name.trim();
        if (cleaned.isEmpty() || cleaned.length() > 100) {
            throw new IllegalArgumentException("Der Name muss 1–100 Zeichen lang sein.");
        }
        return cleaned;
    }

    private void requireColor(String color) {
        if (color == null || !COLOR.matcher(color).matches()) {
            throw new IllegalArgumentException("Die Farbe muss im Format #RRGGBB angegeben werden.");
        }
    }

    private void requireLinkable(Long userId, Long memberId) {
        if (userId == null) {
            return;
        }
        if (!users.existsById(userId)) {
            throw new IllegalArgumentException("Das ausgewählte Benutzerkonto existiert nicht.");
        }
        Optional<FamilyMember> existing = members.findByUserId(userId);
        if (existing.isPresent() && !existing.get().getId().equals(memberId)) {
            throw new IllegalArgumentException(
                    "Dieses Benutzerkonto ist bereits mit einem anderen Mitglied verknüpft.");
        }
    }
}
