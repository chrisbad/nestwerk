package club.badernet.nestwerk.web;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestParam;

/** Makes the origin of the current request ({@code back}) available to every template as {@code nav}. */
@ControllerAdvice
public class NavigationAdvice {

    private static final String DEFAULT_LABEL = "Zurück";

    private final Navigation navigation;

    public NavigationAdvice(Navigation navigation) {
        this.navigation = navigation;
    }

    @ModelAttribute("nav")
    NavigationContext navigationContext(@RequestParam(required = false) String back) {
        String origin = SafeRedirect.target(back, null);
        String label = navigation.forPath(origin).map(NavigationItem::label).orElse(DEFAULT_LABEL);
        return new NavigationContext(origin, label);
    }
}
