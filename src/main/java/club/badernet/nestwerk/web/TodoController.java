package club.badernet.nestwerk.web;

import club.badernet.nestwerk.security.CurrentUser;
import club.badernet.nestwerk.todo.AssigneeRef;
import club.badernet.nestwerk.todo.Todo;
import club.badernet.nestwerk.todo.TodoAccess;
import club.badernet.nestwerk.todo.TodoConflictException;
import club.badernet.nestwerk.todo.TodoFilter;
import club.badernet.nestwerk.todo.TodoGroup;
import club.badernet.nestwerk.todo.TodoPriority;
import club.badernet.nestwerk.todo.TodoQuery;
import club.badernet.nestwerk.todo.TodoService;
import club.badernet.nestwerk.todo.TodoStatus;
import club.badernet.nestwerk.todo.TodoTiming;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Controller
@RequestMapping("/todos")
public class TodoController {

    private final TodoService todos;
    private final TodoQuery query;
    private final TodoAccess access;
    private final TodoViews views;
    private final MarkdownRenderer markdown;
    private final CurrentUser currentUser;
    private final UserRepository users;

    public TodoController(TodoService todos, TodoQuery query, TodoAccess access, TodoViews views,
                          MarkdownRenderer markdown, CurrentUser currentUser, UserRepository users) {
        this.todos = todos;
        this.query = query;
        this.access = access;
        this.views = views;
        this.markdown = markdown;
        this.currentUser = currentUser;
        this.users = users;
    }

    /** Eine Gruppe mit bereits aufbereiteten Zeilen. */
    public record RowGroup(TodoTiming timing, List<TodoViews.TodoRow> rows) {
    }

    @GetMapping
    String list(@RequestParam(required = false) TodoFilter.Involvement involvement,
                @RequestParam(required = false) String assignee,
                @RequestParam(required = false) TodoStatus status,
                @RequestParam(required = false) TodoTiming timing,
                @RequestParam(required = false) TodoPriority priority,
                @RequestParam(required = false) String tag,
                @RequestParam(required = false) String q,
                @RequestParam(defaultValue = "false") boolean closed,
                Model model) {
        User viewer = requireModule();
        AssigneeRef assigneeRef = null;
        if (assignee != null && !assignee.isBlank()) {
            assigneeRef = AssigneeRef.parse(assignee);
        }
        TodoFilter filter = new TodoFilter(involvement == null ? TodoFilter.Involvement.ALL : involvement,
                assigneeRef, status, timing, priority, blankToNull(tag), blankToNull(q), closed);
        TodoViews.Labels labels = views.labels(viewer);
        LocalDate today = query.today();
        List<RowGroup> groups = new ArrayList<>();
        int count = 0;
        for (TodoGroup group : query.list(viewer, filter)) {
            List<TodoViews.TodoRow> rows = group.todos().stream()
                    .map(t -> views.row(t, viewer, labels, today)).toList();
            count += rows.size();
            groups.add(new RowGroup(group.timing(), rows));
        }
        model.addAttribute("groups", groups);
        model.addAttribute("count", count);
        model.addAttribute("filter", filter);
        model.addAttribute("assigneeKey", assigneeRef == null ? "" : assigneeRef.key());
        model.addAttribute("labels", labels);
        model.addAttribute("tags", query.tags(viewer));
        model.addAttribute("canCreate", access.canCreate(viewer));
        model.addAttribute("involvements", TodoFilter.Involvement.values());
        model.addAttribute("statuses", TodoStatus.values());
        model.addAttribute("timings", TodoTiming.values());
        model.addAttribute("priorities", TodoPriority.values());
        return "todos/list";
    }

    @GetMapping("/new")
    String newForm(Model model) {
        User viewer = requireModule();
        if (!access.canCreate(viewer)) {
            throw new AccessDeniedException("Keine Berechtigung zum Anlegen von ToDos");
        }
        TodoForm form = new TodoForm();
        form.setResponsibleUserId(viewer.getId());
        prepareForm(model, viewer, form, null);
        return "todos/form";
    }

    @PostMapping
    String create(@ModelAttribute("form") TodoForm form, Model model, RedirectAttributes redirect) {
        User actor = requireModule();
        try {
            Todo todo = todos.create(form.toInput(), actor);
            redirect.addFlashAttribute("success", "ToDo wurde angelegt.");
            return "redirect:/todos/" + todo.getId();
        } catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage());
            prepareForm(model, actor, form, null);
            return "todos/form";
        }
    }

    @GetMapping("/{id}")
    String detail(@PathVariable Long id, Model model) {
        User viewer = requireModule();
        Todo todo = todos.get(id, viewer);
        TodoViews.Labels labels = views.labels(viewer);
        model.addAttribute("todo", todo);
        model.addAttribute("row", views.row(todo, viewer, labels, query.today()));
        model.addAttribute("labels", labels);
        model.addAttribute("descriptionHtml", markdown.render(todo.getDescription()));
        model.addAttribute("completionLines", views.completionLines(todo, labels));
        model.addAttribute("canEdit", access.can(viewer, "EDIT", todo) || access.can(viewer, "ASSIGN", todo)
                || access.can(viewer, "CHANGE_RESPONSIBLE", todo));
        model.addAttribute("canStatus", access.can(viewer, "CHANGE_STATUS", todo));
        model.addAttribute("canDelete", access.can(viewer, "DELETE", todo));
        model.addAttribute("statuses", TodoStatus.values());
        model.addAttribute("availableFromLabel", TodoViews.longDate(todo.getAvailableFrom()));
        model.addAttribute("dueOnLabel", TodoViews.longDate(todo.getDueOn()));
        model.addAttribute("followUpLabel", TodoViews.longDate(todo.getFollowUpOn()));
        return "todos/detail";
    }

    @GetMapping("/{id}/edit")
    String editForm(@PathVariable Long id, Model model) {
        User viewer = requireModule();
        Todo todo = todos.get(id, viewer);
        if (!access.can(viewer, "EDIT", todo) && !access.can(viewer, "ASSIGN", todo)
                && !access.can(viewer, "CHANGE_RESPONSIBLE", todo)) {
            throw new AccessDeniedException("Keine Berechtigung zum Bearbeiten dieses ToDos");
        }
        prepareForm(model, viewer, TodoForm.from(todo), todo);
        return "todos/form";
    }

    @PostMapping("/{id}")
    String update(@PathVariable Long id, @ModelAttribute("form") TodoForm form, Model model,
                  RedirectAttributes redirect) {
        User actor = requireModule();
        Todo todo = todos.get(id, actor);
        // Gesperrte Felder werden nicht gesendet; ihren Stand übernehmen statt sie zu leeren.
        if (!access.can(actor, "EDIT", todo)) {
            form.keepContentOf(todo);
        }
        if (!access.can(actor, "ASSIGN", todo)) {
            form.keepAssigneesOf(todo);
        }
        if (!access.can(actor, "CHANGE_RESPONSIBLE", todo)) {
            form.keepResponsibleOf(todo);
        }
        try {
            int version = form.getVersion() == null ? todo.getVersion() : form.getVersion();
            todos.update(id, form.toInput(), version, actor);
            redirect.addFlashAttribute("success", "ToDo wurde gespeichert.");
            return "redirect:/todos/" + id;
        } catch (IllegalArgumentException | TodoConflictException e) {
            model.addAttribute("error", e.getMessage());
            prepareForm(model, actor, form, todo);
            return "todos/form";
        }
    }

    @PostMapping("/{id}/check")
    String check(@PathVariable Long id, @RequestHeader(name = "HX-Request", required = false) String htmx,
                 @RequestParam(required = false) String back, Model model, RedirectAttributes redirect) {
        return statusAction(id, htmx, back, model, redirect, "Abgehakt.", (todo, actor) -> todos.check(todo, actor));
    }

    @PostMapping("/{id}/uncheck")
    String uncheck(@PathVariable Long id, @RequestHeader(name = "HX-Request", required = false) String htmx,
                   @RequestParam(required = false) String back, Model model, RedirectAttributes redirect) {
        return statusAction(id, htmx, back, model, redirect, "Wieder geöffnet.", (todo, actor) -> todos.uncheck(todo, actor));
    }

    @PostMapping("/{id}/take")
    String take(@PathVariable Long id, RedirectAttributes redirect) {
        todos.take(id, requireModule());
        redirect.addFlashAttribute("success", "Du kümmerst dich darum.");
        return "redirect:/todos/" + id;
    }

    @PostMapping("/{id}/status")
    String changeStatus(@PathVariable Long id, @RequestParam TodoStatus status, RedirectAttributes redirect) {
        todos.changeStatus(id, status, requireModule());
        redirect.addFlashAttribute("success", "Status: " + status.label() + ".");
        return "redirect:/todos/" + id;
    }

    @PostMapping("/{id}/delete")
    String delete(@PathVariable Long id, RedirectAttributes redirect) {
        todos.delete(id, requireModule());
        redirect.addFlashAttribute("success", "ToDo wurde gelöscht.");
        return "redirect:/todos";
    }

    // --- intern ----------------------------------------------------------------

    private interface StatusChange {
        void apply(Long id, User actor);
    }

    private String statusAction(Long id, String htmx, String back, Model model, RedirectAttributes redirect,
                                String message, StatusChange change) {
        User actor = requireModule();
        String error = null;
        try {
            change.apply(id, actor);
        } catch (IllegalArgumentException e) {
            error = e.getMessage();
        }
        if ("true".equals(htmx)) {
            Todo todo = todos.get(id, actor);
            model.addAttribute("row", views.row(todo, actor, views.labels(actor), query.today()));
            model.addAttribute("rowError", error);
            return "todos/fragments :: row";
        }
        redirect.addFlashAttribute(error == null ? "success" : "error", error == null ? message : error);
        return "redirect:" + safeBack(back, "/todos/" + id);
    }

    private void prepareForm(Model model, User viewer, TodoForm form, Todo todo) {
        boolean isNew = todo == null;
        Todo probe = isNew ? new Todo("", viewer.getId()) : todo;
        model.addAttribute("form", form);
        model.addAttribute("isNew", isNew);
        model.addAttribute("todo", todo);
        model.addAttribute("action", isNew ? "/todos" : "/todos/" + todo.getId());
        model.addAttribute("labels", views.labels(viewer));
        model.addAttribute("tags", query.tags(viewer));
        model.addAttribute("priorities", TodoPriority.values());
        model.addAttribute("activeUsers", users.findAllByOrderByUsernameAsc().stream().filter(User::isEnabled).toList());
        model.addAttribute("canContent", isNew || access.can(viewer, "EDIT", todo));
        model.addAttribute("canAssign", isNew || access.can(viewer, "ASSIGN", todo));
        model.addAttribute("canResponsible", access.can(viewer, "CHANGE_RESPONSIBLE", probe));
        model.addAttribute("canPrivate", access.canCreatePrivate(viewer) || (!isNew && todo.isPrivateTodo()));
    }

    private User requireModule() {
        User user = currentUser.require();
        if (!access.mayOpen(user.getUsername())) {
            throw new AccessDeniedException("Keine Berechtigung für ToDos");
        }
        return user;
    }

    /** Nur lokale Pfade als Rücksprung zulassen. */
    static String safeBack(String back, String fallback) {
        if (back != null && back.startsWith("/") && !back.startsWith("//") && !back.contains("\\")) {
            return back;
        }
        return fallback;
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }
}
