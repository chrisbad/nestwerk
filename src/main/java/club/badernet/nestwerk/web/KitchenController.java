package club.badernet.nestwerk.web;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Einstieg in die Küche. */
@Controller
@PreAuthorize("hasAuthority('KITCHEN_VIEW')")
public class KitchenController {

    @GetMapping("/kitchen")
    String start() {
        return "redirect:/kitchen/recipes";
    }
}
