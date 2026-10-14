package club.badernet.nestwerk.web;

import club.badernet.nestwerk.security.CurrentUser;
import club.badernet.nestwerk.todo.AssigneeRef;
import club.badernet.nestwerk.todo.Todo;
import club.badernet.nestwerk.todo.TodoAccess;
import club.badernet.nestwerk.todo.TodoConflictException;
import club.badernet.nestwerk.todo.TodoFilter;
import club.badernet.nestwerk.attachment.AttachmentService;
import club.badernet.nestwerk.notification.Channel;
import club.badernet.nestwerk.notification.NotificationPreferences;
import club.badernet.nestwerk.todo.TodoComment;
import club.badernet.nestwerk.todo.TodoCommentService;
import club.badernet.nestwerk.todo.TodoGroup;
import club.badernet.nestwerk.todo.TodoHistoryEntry;
import club.badernet.nestwerk.todo.TodoHistoryRepository;
import club.badernet.nestwerk.todo.TodoLinkType;
import club.badernet.nestwerk.todo.TodoNotFoundException;
import club.badernet.nestwerk.todo.TodoStructureService;
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
    private final TodoStructureService structure;
    private final TodoCommentService comments;
    private final TodoHistoryRepository history;
    private final TodoHistoryFormatter historyFormatter;
    private final AttachmentService attachments;
    private final NotificationPreferences preferences;

    public TodoController(TodoService todos, TodoQuery query, TodoAccess access, TodoViews views,
                          MarkdownRenderer markdown, CurrentUser currentUser, UserRepository users,
                          TodoStructureService structure, TodoCommentService comments,
                          TodoHistoryRepository history, TodoHistoryFormatter historyFormatter,
                          AttachmentService attachments, NotificationPreferences preferences) {
        this.preferences = preferences;
        this.structure = structure;
        this.comments = comments;
        this.history = history;
        this.historyFormatter = historyFormatter;
        this.attachments = attachments;
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
    String list(@ModelAttribute("params") TodoFilterParams params, Model model) {
        User viewer = requireModule();
        TodoFilter filter = params.toFilter();
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
        addFilterAttributes(model, viewer, params, filter, labels, "list");
        return "todos/list";
    }

    /** Spalten des Boards; Erledigt zeigt nur die letzten 14 Tage. */
    public record BoardColumn(TodoStatus status, List<TodoViews.TodoRow> rows) {
    }

    static final int BOARD_DONE_DAYS = 14;

    @GetMapping("/board")
    String board(@ModelAttribute("params") TodoFilterParams params, Model model) {
        User viewer = requireModule();
        TodoFilter filter = params.toFilter().withIncludeClosed(true);
        TodoViews.Labels labels = views.labels(viewer);
        LocalDate today = query.today();
        java.time.Instant doneSince = java.time.Instant.now().minus(java.time.Duration.ofDays(BOARD_DONE_DAYS));
        java.util.Map<TodoStatus, List<TodoViews.TodoRow>> byStatus = new java.util.EnumMap<>(TodoStatus.class);
        for (TodoStatus s : TodoStatus.values()) {
            byStatus.put(s, new ArrayList<>());
        }
        query.list(viewer, filter).stream().flatMap(g -> g.todos().stream())
                .filter(t -> t.getStatus() != TodoStatus.DONE || t.getCompletedAt() == null
                        || t.getCompletedAt().isAfter(doneSince))
                .sorted(TodoQuery.ORDER)
                .forEach(t -> byStatus.get(t.getStatus()).add(views.row(t, viewer, labels, today)));
        List<BoardColumn> columns = new ArrayList<>();
        byStatus.forEach((status, rows) -> columns.add(new BoardColumn(status, rows)));
        model.addAttribute("columns", columns);
        model.addAttribute("statuses", TodoStatus.values());
        addFilterAttributes(model, viewer, params, params.toFilter(), labels, "board");
        return "todos/board";
    }

    /** Ein Knoten im Baum mit Fortschritt seiner Unteraufgaben. */
    public record TreeNode(TodoViews.TodoRow row, TodoStructureService.Children children) {
    }

    @GetMapping("/tree")
    String tree(@ModelAttribute("params") TodoFilterParams params, Model model) {
        User viewer = requireModule();
        TodoFilter filter = params.toFilter();
        TodoViews.Labels labels = views.labels(viewer);
        LocalDate today = query.today();
        List<Todo> listed = query.list(viewer, filter).stream().flatMap(g -> g.todos().stream()).toList();
        java.util.Set<Long> listedIds = new java.util.HashSet<>(listed.stream().map(Todo::getId).toList());
        List<TreeNode> roots = listed.stream()
                .filter(t -> t.getParentId() == null || !listedIds.contains(t.getParentId()))
                .sorted(TodoQuery.ORDER)
                .map(t -> new TreeNode(views.row(t, viewer, labels, today), structure.children(t.getId(), viewer)))
                .toList();
        model.addAttribute("roots", roots);
        addFilterAttributes(model, viewer, params, filter, labels, "tree");
        return "todos/tree";
    }

    @GetMapping("/{id}/children")
    String children(@PathVariable Long id, Model model) {
        User viewer = requireModule();
        todos.get(id, viewer);
        TodoStructureService.Children children = structure.children(id, viewer);
        TodoViews.Labels labels = views.labels(viewer);
        LocalDate today = query.today();
        model.addAttribute("nodes", children.visible().stream()
                .map(t -> new TreeNode(views.row(t, viewer, labels, today), structure.children(t.getId(), viewer)))
                .toList());
        model.addAttribute("hiddenCount", children.hiddenCount());
        return "todos/tree :: children";
    }

    private void addFilterAttributes(Model model, User viewer, TodoFilterParams params, TodoFilter filter,
                                     TodoViews.Labels labels, String view) {
        model.addAttribute("filter", filter);
        model.addAttribute("assigneeKey", filter.assignee() == null ? "" : filter.assignee().key());
        model.addAttribute("labels", labels);
        model.addAttribute("tags", query.tags(viewer));
        model.addAttribute("canCreate", access.canCreate(viewer));
        model.addAttribute("involvements", TodoFilter.Involvement.values());
        model.addAttribute("statuses", TodoStatus.values());
        model.addAttribute("timings", TodoTiming.values());
        model.addAttribute("priorities", TodoPriority.values());
        String qs = params.queryString();
        model.addAttribute("viewQuery", qs.isEmpty() ? "" : "?" + qs);
        model.addAttribute("view", view);
    }

    @GetMapping("/new")
    String newForm(@RequestParam(required = false) Long parent, Model model) {
        User viewer = requireModule();
        if (!access.canCreate(viewer)) {
            throw new AccessDeniedException("Keine Berechtigung zum Anlegen von ToDos");
        }
        TodoForm form = new TodoForm();
        form.setResponsibleUserId(viewer.getId());
        if (parent != null) {
            form.setParentId(todos.get(parent, viewer).getId());
        }
        prepareForm(model, viewer, form, null);
        return "todos/form";
    }

    @PostMapping
    String create(@ModelAttribute("form") TodoForm form, Model model, RedirectAttributes redirect) {
        User actor = requireModule();
        try {
            Todo todo = form.getParentId() == null ? todos.create(form.toInput(), actor)
                    : structure.createChild(form.getParentId(), form.toInput(), actor);
            redirect.addFlashAttribute("success", "ToDo wurde angelegt.");
            return "redirect:/todos/" + todo.getId();
        } catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage());
            prepareForm(model, actor, form, null);
            return "todos/form";
        }
    }

    /** Ein Eintrag im Zeitstrahl: Kommentar oder Verlaufseintrag. */
    public record TimelineItem(java.time.Instant at, String when, String actor, boolean comment, Long commentId,
                               String html, String text,
                               List<club.badernet.nestwerk.attachment.Attachment> attachments,
                               boolean canEdit, boolean canDelete, String body, boolean edited) {
    }

    static final java.time.format.DateTimeFormatter TIMESTAMP =
            java.time.format.DateTimeFormatter.ofPattern("d. MMM yyyy, HH:mm", java.util.Locale.GERMANY);

    @GetMapping("/{id}")
    String detail(@PathVariable Long id, @RequestParam(required = false) String timeline, Model model) {
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
        model.addAttribute("repeatLabel", TodoViews.repeatLabel(todo.getRrule()));
        TodoStructureService.Children children = structure.children(id, viewer);
        model.addAttribute("children", children);
        model.addAttribute("childRows", children.visible().stream()
                .map(t -> views.row(t, viewer, labels, query.today())).toList());
        model.addAttribute("ancestors", structure.ancestors(id, viewer));
        model.addAttribute("links", structure.links(id, viewer));
        model.addAttribute("descendantCount", structure.descendantCount(id));
        boolean canLink = access.can(viewer, "LINK", todo);
        model.addAttribute("canLink", canLink);
        model.addAttribute("canAddChild", canLink && access.canCreate(viewer));
        model.addAttribute("linkTypes", TodoLinkType.values());
        model.addAttribute("linkCandidates", canLink ? query.list(viewer, TodoFilter.defaults()).stream()
                .flatMap(g -> g.todos().stream()).filter(t -> !t.getId().equals(id)).limit(300).toList() : List.of());

        // Zusammenarbeit
        boolean onlyComments = "comments".equals(timeline);
        List<TodoComment> todoComments = comments.forTodo(id, viewer);
        java.util.Map<Long, List<club.badernet.nestwerk.attachment.Attachment>> commentFiles =
                attachments.forComments(todoComments.stream().map(TodoComment::getId).toList());
        List<TimelineItem> items = new ArrayList<>();
        for (TodoComment c : todoComments) {
            boolean own = c.getAuthorId().equals(viewer.getId());
            items.add(new TimelineItem(c.getCreatedAt(), when(c.getCreatedAt()), labels.user(c.getAuthorId()), true, c.getId(),
                    markdown.render(c.getBody()), null, commentFiles.getOrDefault(c.getId(), List.of()),
                    own, own || viewer.isAdministrator(), c.getBody(), c.getEditedAt() != null));
        }
        if (!onlyComments) {
            for (TodoHistoryEntry e : history.findAllByTodoIdOrderByAtAscIdAsc(id)) {
                items.add(new TimelineItem(e.getAt(), when(e.getAt()), labels.user(e.getActorId()), false, null, null,
                        historyFormatter.text(e, labels, viewer), List.of(), false, false, null, false));
            }
        }
        items.sort(java.util.Comparator.comparing(TimelineItem::at));
        model.addAttribute("timeline", items);
        model.addAttribute("onlyComments", onlyComments);
        model.addAttribute("canComment", access.can(viewer, "COMMENT", todo));
        model.addAttribute("canAttach", access.can(viewer, "ATTACH", todo));
        model.addAttribute("attachments", attachments.forTodo(id));
        model.addAttribute("watching", todo.getWatcherUserIds().contains(viewer.getId()));
        model.addAttribute("watchers", todo.getWatcherUserIds().stream().map(labels::user).sorted().toList());
        model.addAttribute("watcherIds", todo.getWatcherUserIds());
        model.addAttribute("currentUserId", viewer.getId());
        model.addAttribute("canOverride", access.can(viewer, "NOTIFY_OVERRIDE", todo));
        model.addAttribute("overrideEmail", preferences.choice(NotificationPreferences.Scope.TODO, id,
                NotificationPreferences.ALL, Channel.EMAIL));
        model.addAttribute("overridePush", preferences.choice(NotificationPreferences.Scope.TODO, id,
                NotificationPreferences.ALL, Channel.PUSH));
        model.addAttribute("choices", NotificationPreferences.Choice.values());
        model.addAttribute("canWatch", access.can(viewer, "WATCH", todo));
        boolean canManageWatchers = access.can(viewer, "MANAGE_WATCHERS", todo);
        model.addAttribute("canManageWatchers", canManageWatchers);
        model.addAttribute("mentionable", users.findAllByOrderByUsernameAsc().stream()
                .filter(User::isEnabled).filter(u -> access.can(u, "VIEW", todo)).map(User::getUsername).toList());
        model.addAttribute("activeUsers", users.findAllByOrderByUsernameAsc().stream().filter(User::isEnabled).toList());
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

    /** Board: Statuswechsel per htmx; 204 bei Erfolg, 409 mit Meldung, wenn er nicht erlaubt ist. */
    @PostMapping(value = "/{id}/status", headers = "HX-Request=true")
    @org.springframework.web.bind.annotation.ResponseBody
    org.springframework.http.ResponseEntity<String> changeStatusInline(@PathVariable Long id,
                                                                       @RequestParam TodoStatus status) {
        try {
            todos.changeStatus(id, status, requireModule());
            return org.springframework.http.ResponseEntity.noContent().build();
        } catch (AccessDeniedException | IllegalArgumentException e) {
            return org.springframework.http.ResponseEntity.status(org.springframework.http.HttpStatus.CONFLICT)
                    .contentType(new org.springframework.http.MediaType("text", "plain", java.nio.charset.StandardCharsets.UTF_8))
                    .body("Keine Berechtigung, den Status dieses ToDos zu ändern.");
        }
    }

    @PostMapping("/{id}/status")
    String changeStatus(@PathVariable Long id, @RequestParam TodoStatus status, RedirectAttributes redirect) {
        todos.changeStatus(id, status, requireModule());
        redirect.addFlashAttribute("success", "Status: " + status.label() + ".");
        return "redirect:/todos/" + id;
    }

    @PostMapping("/{id}/links")
    String addLink(@PathVariable Long id, @RequestParam String type, @RequestParam(required = false) String target,
                   RedirectAttributes redirect) {
        User actor = requireModule();
        Long targetId = parseTarget(target);
        try {
            if (targetId == null) {
                throw new IllegalArgumentException("Bitte ein ToDo aus der Vorschlagsliste wählen.");
            }
            if ("PARENT".equals(type)) {
                structure.moveTo(id, targetId, actor);
                redirect.addFlashAttribute("success", "ToDo wurde untergeordnet.");
            } else {
                structure.link(id, targetId, TodoLinkType.valueOf(type), actor);
                redirect.addFlashAttribute("success", "Verknüpfung wurde angelegt.");
            }
        } catch (IllegalArgumentException | TodoNotFoundException e) {
            redirect.addFlashAttribute("error", e instanceof TodoNotFoundException
                    ? "Dieses ToDo gibt es nicht." : e.getMessage());
        }
        return "redirect:/todos/" + id;
    }

    @PostMapping("/{id}/links/{linkId}/delete")
    String removeLink(@PathVariable Long id, @PathVariable Long linkId, RedirectAttributes redirect) {
        structure.unlink(linkId, requireModule());
        redirect.addFlashAttribute("success", "Verknüpfung wurde gelöst.");
        return "redirect:/todos/" + id;
    }

    @PostMapping("/{id}/detach")
    String detach(@PathVariable Long id, RedirectAttributes redirect) {
        structure.moveTo(id, null, requireModule());
        redirect.addFlashAttribute("success", "ToDo steht jetzt auf oberster Ebene.");
        return "redirect:/todos/" + id;
    }

    // --- Kommentare, Beobachter, Anhänge ------------------------------------------

    @PostMapping("/{id}/comments")
    String addComment(@PathVariable Long id, @RequestParam String body,
                      @RequestParam(name = "files", required = false) List<org.springframework.web.multipart.MultipartFile> files,
                      RedirectAttributes redirect) {
        User actor = requireModule();
        try {
            TodoComment comment = comments.add(id, body, actor);
            storeAll(club.badernet.nestwerk.attachment.AttachmentOwner.comment(comment.getId()), files, actor);
            redirect.addFlashAttribute("success", "Kommentar wurde gespeichert.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/todos/" + id + "#timeline";
    }

    @PostMapping("/{id}/comments/{commentId}/edit")
    String editComment(@PathVariable Long id, @PathVariable Long commentId, @RequestParam String body,
                       RedirectAttributes redirect) {
        try {
            comments.edit(commentId, body, requireModule());
            redirect.addFlashAttribute("success", "Kommentar wurde geändert.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/todos/" + id + "#timeline";
    }

    @PostMapping("/{id}/comments/{commentId}/delete")
    String deleteComment(@PathVariable Long id, @PathVariable Long commentId, RedirectAttributes redirect) {
        comments.delete(commentId, requireModule());
        redirect.addFlashAttribute("success", "Kommentar wurde gelöscht.");
        return "redirect:/todos/" + id + "#timeline";
    }

    @PostMapping("/{id}/follow-up")
    String followUp(@PathVariable Long id, @RequestParam TodoService.FollowUpAction action,
                    @RequestParam(required = false) String back, RedirectAttributes redirect) {
        todos.followUp(id, action, requireModule());
        redirect.addFlashAttribute("success", switch (action) {
            case DONE -> "ToDo ist erledigt.";
            case DISCARD -> "ToDo wurde verworfen.";
            case WEEK -> "Wiedervorlage in einer Woche.";
            case MONTH -> "Wiedervorlage in einem Monat.";
        });
        return "redirect:" + safeBack(back, "/todos/" + id);
    }

    @PostMapping("/{id}/notification-override")
    String notificationOverride(@PathVariable Long id,
                                @RequestParam(name = "EMAIL", defaultValue = "DEFAULT") NotificationPreferences.Choice email,
                                @RequestParam(name = "PUSH", defaultValue = "DEFAULT") NotificationPreferences.Choice push,
                                RedirectAttributes redirect) {
        User actor = requireModule();
        Todo todo = todos.get(id, actor);
        if (!access.can(actor, "NOTIFY_OVERRIDE", todo)) {
            throw new AccessDeniedException("Keine Berechtigung, Benachrichtigungen dieses ToDos zu ändern");
        }
        preferences.set(NotificationPreferences.Scope.TODO, id, NotificationPreferences.ALL, Channel.EMAIL, email);
        preferences.set(NotificationPreferences.Scope.TODO, id, NotificationPreferences.ALL, Channel.PUSH, push);
        redirect.addFlashAttribute("success", "Benachrichtigungen für dieses ToDo wurden gespeichert.");
        return "redirect:/todos/" + id;
    }

    @PostMapping("/{id}/watch")
    String watch(@PathVariable Long id, RedirectAttributes redirect) {
        todos.watch(id, requireModule());
        redirect.addFlashAttribute("success", "Du beobachtest dieses ToDo.");
        return "redirect:/todos/" + id;
    }

    @PostMapping("/{id}/unwatch")
    String unwatch(@PathVariable Long id, RedirectAttributes redirect) {
        todos.unwatch(id, requireModule());
        redirect.addFlashAttribute("success", "Du beobachtest dieses ToDo nicht mehr.");
        return "redirect:/todos/" + id;
    }

    @PostMapping("/{id}/watchers")
    String addWatcher(@PathVariable Long id, @RequestParam Long userId, RedirectAttributes redirect) {
        try {
            todos.addWatcher(id, userId, requireModule());
            redirect.addFlashAttribute("success", "Beobachter wurde hinzugefügt.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/todos/" + id;
    }

    @PostMapping("/{id}/watchers/{userId}/delete")
    String removeWatcher(@PathVariable Long id, @PathVariable Long userId, RedirectAttributes redirect) {
        todos.removeWatcher(id, userId, requireModule());
        redirect.addFlashAttribute("success", "Beobachter wurde entfernt.");
        return "redirect:/todos/" + id;
    }

    @PostMapping("/{id}/attachments")
    String upload(@PathVariable Long id,
                  @RequestParam(name = "files", required = false) List<org.springframework.web.multipart.MultipartFile> files,
                  RedirectAttributes redirect) {
        User actor = requireModule();
        try {
            int stored = storeAll(club.badernet.nestwerk.attachment.AttachmentOwner.todo(id), files, actor);
            redirect.addFlashAttribute(stored == 0 ? "error" : "success",
                    stored == 0 ? "Bitte eine Datei auswählen." : (stored == 1 ? "Datei wurde hochgeladen." : stored + " Dateien wurden hochgeladen."));
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/todos/" + id + "#attachments";
    }

    /** Speichert alle nicht leeren Dateien; bricht beim ersten ungültigen mit Meldung ab. */
    private int storeAll(club.badernet.nestwerk.attachment.AttachmentOwner owner,
                         List<org.springframework.web.multipart.MultipartFile> files, User actor) {
        int stored = 0;
        if (files != null) {
            for (org.springframework.web.multipart.MultipartFile f : files) {
                if (f != null && !f.isEmpty()) {
                    attachments.store(owner, f, actor);
                    stored++;
                }
            }
        }
        return stored;
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
        model.addAttribute("parent", isNew && form.getParentId() != null ? todos.get(form.getParentId(), viewer) : null);
    }

    private User requireModule() {
        User user = currentUser.require();
        if (!access.mayOpen(user.getUsername())) {
            throw new AccessDeniedException("Keine Berechtigung für ToDos");
        }
        return user;
    }

    private String when(java.time.Instant at) {
        return TIMESTAMP.format(at.atZone(query.zone()));
    }

    /** Liest „#12 Titel“ oder „12“; sonst {@code null}. */
    static Long parseTarget(String target) {
        if (target == null) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^\\s*#?(\\d{1,18})\\b").matcher(target);
        return m.find() ? Long.valueOf(m.group(1)) : null;
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
