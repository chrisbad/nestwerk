package club.badernet.nestwerk.household;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface HouseholdDeviceRepository extends JpaRepository<HouseholdDevice, Long> {

    Optional<HouseholdDevice> findByTokenHash(String tokenHash);

    Optional<HouseholdDevice> findByPairingHash(String pairingHash);

    List<HouseholdDevice> findAllByOrderByCreatedAtDesc();
}
