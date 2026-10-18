package club.badernet.nestwerk.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Bedienungsanleitung für Endanwender; für jeden angemeldeten Benutzer sichtbar. */
@Controller
public class ManualController {

    @GetMapping("/manual")
    String manual() {
        return "manual";
    }
}
