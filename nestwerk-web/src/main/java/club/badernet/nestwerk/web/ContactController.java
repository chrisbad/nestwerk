package club.badernet.nestwerk.web;

import club.badernet.nestwerk.contact.Contact;
import club.badernet.nestwerk.contact.ContactService;
import club.badernet.nestwerk.security.CurrentUser;
import club.badernet.nestwerk.todo.AssigneeRef;
import club.badernet.nestwerk.todo.Todo;
import club.badernet.nestwerk.todo.TodoAccess;
import club.badernet.nestwerk.todo.TodoFilter;
import club.badernet.nestwerk.todo.TodoQuery;
import club.badernet.nestwerk.user.User;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

@Controller
@RequestMapping("/contacts")
public class ContactController {

    static final String WITHOUT_CATEGORY = "Ohne Kategorie";

    private final ContactService contacts;
    private final CurrentUser currentUser;
    private final TodoQuery todoQuery;
    private final TodoAccess todoAccess;
    private final club.badernet.nestwerk.attachment.AttachmentService attachments;

    public ContactController(ContactService contacts, CurrentUser currentUser, TodoQuery todoQuery,
                             TodoAccess todoAccess, club.badernet.nestwerk.attachment.AttachmentService attachments) {
        this.attachments = attachments;
        this.contacts = contacts;
        this.currentUser = currentUser;
        this.todoQuery = todoQuery;
        this.todoAccess = todoAccess;
    }

    @GetMapping
    String list(@RequestParam(required = false) String q, Model model) {
        User viewer = requireModule();
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.GERMAN);
        Map<String, List<Contact>> grouped = new TreeMap<>((a, b) -> {
            if (a.equals(b)) return 0;
            if (a.equals(WITHOUT_CATEGORY)) return 1;
            if (b.equals(WITHOUT_CATEGORY)) return -1;
            return a.compareToIgnoreCase(b);
        });
        for (Contact c : contacts.findVisible(viewer)) {
            if (!needle.isEmpty() && !matches(c, needle)) {
                continue;
            }
            String key = c.getCategory() == null ? WITHOUT_CATEGORY : c.getCategory();
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(c);
        }
        model.addAttribute("groups", grouped);
        model.addAttribute("q", q);
        model.addAttribute("canCreate", contacts.canCreate(viewer));
        return "contacts/list";
    }

    @GetMapping("/new")
    String newForm(Model model) {
        User viewer = requireModule();
        if (!contacts.canCreate(viewer)) {
            throw new AccessDeniedException("Keine Berechtigung zum Anlegen von Kontakten");
        }
        model.addAttribute("form", new ContactForm());
        model.addAttribute("action", "/contacts");
        model.addAttribute("isNew", true);
        return "contacts/form";
    }

    @PostMapping
    String create(@ModelAttribute("form") ContactForm form, Model model, RedirectAttributes redirect) {
        User actor = requireModule();
        try {
            Contact c = contacts.create(form.toInput(), actor);
            redirect.addFlashAttribute("success", "Kontakt wurde angelegt.");
            return "redirect:/contacts/" + c.getId();
        } catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage());
            model.addAttribute("action", "/contacts");
            model.addAttribute("isNew", true);
            return "contacts/form";
        }
    }

    @GetMapping("/{id}")
    String detail(@PathVariable Long id, Model model) {
        User viewer = requireModule();
        Contact contact = contacts.get(id, viewer);
        model.addAttribute("contact", contact);
        model.addAttribute("canEdit", contacts.can(viewer, "EDIT", contact));
        model.addAttribute("canDelete", contacts.can(viewer, "DELETE", contact));
        List<Todo> openTodos = List.of();
        if (todoAccess.mayOpen(viewer.getUsername())) {
            openTodos = todoQuery.list(viewer, TodoFilter.defaults().withAssignee(AssigneeRef.contact(id))).stream()
                    .flatMap(g -> g.todos().stream()).toList();
        }
        model.addAttribute("openTodos", openTodos);
        model.addAttribute("attachments", attachments.forContact(id));
        model.addAttribute("canAttach", contacts.can(viewer, "ATTACH", contact));
        return "contacts/detail";
    }

    @GetMapping("/{id}/edit")
    String editForm(@PathVariable Long id, Model model) {
        User viewer = requireModule();
        Contact contact = contacts.get(id, viewer);
        if (!contacts.can(viewer, "EDIT", contact)) {
            throw new AccessDeniedException("Keine Berechtigung zum Bearbeiten dieses Kontakts");
        }
        model.addAttribute("form", ContactForm.from(contact));
        model.addAttribute("action", "/contacts/" + id);
        model.addAttribute("isNew", false);
        model.addAttribute("contact", contact);
        return "contacts/form";
    }

    @PostMapping("/{id}")
    String update(@PathVariable Long id, @ModelAttribute("form") ContactForm form, Model model,
                  RedirectAttributes redirect) {
        User actor = requireModule();
        try {
            contacts.update(id, form.toInput(), actor);
            redirect.addFlashAttribute("success", "Kontakt wurde gespeichert.");
            return "redirect:/contacts/" + id;
        } catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage());
            model.addAttribute("action", "/contacts/" + id);
            model.addAttribute("isNew", false);
            model.addAttribute("contact", contacts.get(id, actor));
            return "contacts/form";
        }
    }

    @PostMapping("/{id}/attachments")
    String upload(@PathVariable Long id,
                  @RequestParam(name = "files", required = false) List<org.springframework.web.multipart.MultipartFile> files,
                  RedirectAttributes redirect) {
        User actor = requireModule();
        int stored = 0;
        try {
            if (files != null) {
                for (org.springframework.web.multipart.MultipartFile f : files) {
                    if (f != null && !f.isEmpty()) {
                        attachments.store(club.badernet.nestwerk.attachment.AttachmentOwner.contact(id), f, actor);
                        stored++;
                    }
                }
            }
            redirect.addFlashAttribute(stored == 0 ? "error" : "success",
                    stored == 0 ? "Bitte eine Datei auswählen." : "Datei wurde hochgeladen.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/contacts/" + id;
    }

    @PostMapping("/{id}/delete")
    String delete(@PathVariable Long id, RedirectAttributes redirect) {
        contacts.delete(id, requireModule());
        redirect.addFlashAttribute("success", "Kontakt wurde gelöscht.");
        return "redirect:/contacts";
    }

    private User requireModule() {
        User user = currentUser.require();
        if (!contacts.mayOpen(user.getUsername())) {
            throw new AccessDeniedException("Keine Berechtigung für Kontakte");
        }
        return user;
    }

    private static boolean matches(Contact c, String needle) {
        for (String field : new String[]{c.getName(), c.getCompany(), c.getCategory(), c.getCity()}) {
            if (field != null && field.toLowerCase(Locale.GERMAN).contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
