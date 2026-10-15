package club.badernet.nestwerk.ai;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AiTokenRepository extends JpaRepository<AiToken, Long> {

    Optional<AiToken> findByTokenHash(String tokenHash);

    List<AiToken> findAllByUserIdOrderByIdDesc(Long userId);
}
