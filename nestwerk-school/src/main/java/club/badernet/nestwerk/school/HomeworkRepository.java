package club.badernet.nestwerk.school;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface HomeworkRepository extends JpaRepository<Homework, Long> {

    List<Homework> findAllByYearId(Long yearId);
}
