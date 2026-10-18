package club.badernet.nestwerk.todo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TodoCommentRepository extends JpaRepository<TodoComment, Long> {

    List<TodoComment> findAllByTodoIdOrderByCreatedAtAsc(Long todoId);
}
