package club.badernet.nestwerk.attachment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Set;

public interface AttachmentRepository extends JpaRepository<Attachment, Long> {

    List<Attachment> findAllByTodoIdOrderByUploadedAtAsc(Long todoId);

    List<Attachment> findAllByCommentIdInOrderByUploadedAtAsc(Collection<Long> commentIds);

    List<Attachment> findAllByContactIdOrderByUploadedAtAsc(Long contactId);

    List<Attachment> findAllByRecipeIdOrderByUploadedAtAsc(Long recipeId);

    List<Attachment> findAllByLetterIdOrderByUploadedAtAsc(Long letterId);

    @Query("select a.storageKey from Attachment a")
    Set<String> findAllStorageKeys();
}
