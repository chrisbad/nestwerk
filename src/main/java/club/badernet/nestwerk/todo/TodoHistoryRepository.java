package club.badernet.nestwerk.todo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TodoHistoryRepository extends JpaRepository<TodoHistoryEntry, Long> {

    List<TodoHistoryEntry> findAllByTodoIdOrderByAtAscIdAsc(Long todoId);
}
