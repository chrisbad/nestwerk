package club.badernet.nestwerk.web;

import club.badernet.nestwerk.calendar.CategoryNotFoundException;
import club.badernet.nestwerk.calendar.EventNotFoundException;
import club.badernet.nestwerk.household.ChoreConflictException;
import club.badernet.nestwerk.household.ChoreNotFoundException;
import club.badernet.nestwerk.todo.TodoConflictException;
import club.badernet.nestwerk.todo.TodoNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Turns failures of the calendar's JSON actions into a short message for the toast. */
@RestControllerAdvice(assignableTypes = {CalendarActionsController.class, CalendarChoreActionsController.class,
        CalendarTodoActionsController.class})
class CalendarActionsAdvice {

    @ExceptionHandler({IllegalArgumentException.class, ChoreConflictException.class, TodoConflictException.class})
    ResponseEntity<ActionResult> invalid(RuntimeException e) {
        return ResponseEntity.badRequest().body(ActionResult.of(e.getMessage()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ActionResult> denied() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ActionResult.of("Das darfst du hier nicht ändern."));
    }

    @ExceptionHandler({EventNotFoundException.class, CategoryNotFoundException.class, ChoreNotFoundException.class,
            TodoNotFoundException.class})
    ResponseEntity<ActionResult> missing(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ActionResult.of(e.getMessage()));
    }
}
