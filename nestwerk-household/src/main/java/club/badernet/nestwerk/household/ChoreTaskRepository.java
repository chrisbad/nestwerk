package club.badernet.nestwerk.household;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ChoreTaskRepository extends JpaRepository<ChoreTask, Long> {

    List<ChoreTask> findAllByChoreId(Long choreId);

    List<ChoreTask> findAllByAssigneeMemberIdAndDueOn(Long memberId, LocalDate day);

    Optional<ChoreTask> findByChoreIdAndSlotKey(Long choreId, String slotKey);

    List<ChoreTask> findAllByChoreIdAndStatus(Long choreId, ChoreTaskStatus status);

    List<ChoreTask> findAllByDueOnBetweenAndStatusNot(LocalDate from, LocalDate to, ChoreTaskStatus status);

    List<ChoreTask> findAllByStatusAndDueOnBefore(ChoreTaskStatus status, LocalDate day);

    List<ChoreTask> findAllByStatusAndDueOnLessThanEqual(ChoreTaskStatus status, LocalDate day);

    List<ChoreTask> findAllByStatusAndDueOnGreaterThanEqual(ChoreTaskStatus status, LocalDate day);

    List<ChoreTask> findAllByStatusAndDueOnAndNotifiedDueFalse(ChoreTaskStatus status, LocalDate day);
}
