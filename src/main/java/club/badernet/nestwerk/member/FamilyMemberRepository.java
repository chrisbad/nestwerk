package club.badernet.nestwerk.member;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FamilyMemberRepository extends JpaRepository<FamilyMember, Long> {

    List<FamilyMember> findAllByOrderByNameAsc();

    Optional<FamilyMember> findByUserId(Long userId);
}
