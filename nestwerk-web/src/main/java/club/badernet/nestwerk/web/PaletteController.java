package club.badernet.nestwerk.web;

import club.badernet.nestwerk.security.CurrentUser;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** The entries of the command palette (Strg + K): places to go to, things to create, settings to open. */
@Controller
public class PaletteController {

    /**
     * One hit of the palette.
     *
     * @param group     heading such as "Gehe zu"
     * @param label     German text
     * @param hint      small text next to the label, may be empty
     * @param path      target
     * @param excursion whether the way back should lead here (settings and creating things)
     * @param search    words the entry is found by
     */
    public record Entry(String group, String label, String hint, String path, boolean excursion, String search) {
    }

    private static final String GO_TO = "Gehe zu";
    private static final String CREATE = "Neu anlegen";
    private static final String SETTINGS = "Einstellungen";

    private final Navigation navigation;
    private final Settings settings;
    private final CurrentUser currentUser;

    public PaletteController(Navigation navigation, Settings settings, CurrentUser currentUser) {
        this.navigation = navigation;
        this.settings = settings;
        this.currentUser = currentUser;
    }

    /** Loaded once when the palette is opened for the first time. */
    @GetMapping("/palette")
    String entries(Authentication authentication, Model model) {
        List<Entry> entries = new ArrayList<>();
        Stream.of(NavigationGroup.AREA, NavigationGroup.ADMINISTRATION)
                .flatMap(group -> navigation.visible(authentication, group).stream())
                .forEach(item -> entries.add(new Entry(GO_TO, item.label(), "", item.path(), false, item.label())));
        navigation.quickActions(authentication).forEach(action ->
                entries.add(new Entry(CREATE, action.label(), "", action.path(), true, "neu anlegen " + action.label())));
        settings.list(currentUser.require(), SettingFilter.none()).forEach(entry ->
                entries.add(new Entry(SETTINGS, entry.title(), entry.area().label(), entry.path(), true,
                        entry.title() + " " + entry.description() + " " + String.join(" ", entry.descriptor().keywords()))));
        model.addAttribute("entries", entries);
        return "fragments/palette :: entries";
    }
}
