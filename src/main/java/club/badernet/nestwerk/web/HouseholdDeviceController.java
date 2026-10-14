package club.badernet.nestwerk.web;

import club.badernet.nestwerk.household.DeviceService;
import club.badernet.nestwerk.household.MemberPins;
import club.badernet.nestwerk.household.HouseholdAccess;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import club.badernet.nestwerk.security.CurrentUser;
import club.badernet.nestwerk.user.User;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Optional;

/** Wand-Tablets koppeln und abmelden; PINs fürs Tablet setzen (eigene und – für Verwalter – alle). */
@Controller
public class HouseholdDeviceController {

    private final DeviceService devices;
    private final MemberPins pins;
    private final HouseholdAccess access;
    private final FamilyMemberRepository members;
    private final CurrentUser currentUser;
    private final java.time.Clock clock;

    public HouseholdDeviceController(DeviceService devices, MemberPins pins, HouseholdAccess access,
                                     FamilyMemberRepository members, CurrentUser currentUser, java.time.Clock clock) {
        this.clock = clock;
        this.devices = devices;
        this.pins = pins;
        this.access = access;
        this.members = members;
        this.currentUser = currentUser;
    }

    /** Ein Tablet für die Liste. */
    public record DeviceRow(Long id, String name, boolean active, boolean pairing, String lastSeen) {
    }

    private static final java.time.format.DateTimeFormatter SEEN =
            java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm");

    @GetMapping("/admin/devices")
    String list(Model model) {
        User actor = currentUser.require();
        model.addAttribute("devices", devices.all(actor).stream().map(d -> new DeviceRow(d.getId(), d.getName(),
                d.isActive(), d.isPairing(), d.getLastSeenAt() == null ? null
                : d.getLastSeenAt().atZone(clock.getZone()).format(SEEN))).toList());
        return "admin/devices";
    }

    @PostMapping("/admin/devices")
    String create(@RequestParam(required = false) String name, RedirectAttributes redirect) {
        try {
            DeviceService.Pairing pairing = devices.create(name, currentUser.require());
            redirect.addFlashAttribute("pairingCode", pairing.code());
            redirect.addFlashAttribute("pairingDevice", pairing.device().getName());
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/devices";
    }

    @PostMapping("/admin/devices/{id}/pair")
    String repair(@PathVariable Long id, RedirectAttributes redirect) {
        DeviceService.Pairing pairing = devices.repair(id, currentUser.require());
        redirect.addFlashAttribute("pairingCode", pairing.code());
        redirect.addFlashAttribute("pairingDevice", pairing.device().getName());
        return "redirect:/admin/devices";
    }

    @PostMapping("/admin/devices/{id}/revoke")
    String revoke(@PathVariable Long id, RedirectAttributes redirect) {
        devices.revoke(id, currentUser.require());
        redirect.addFlashAttribute("success", "Das Tablet ist abgemeldet.");
        return "redirect:/admin/devices";
    }

    @GetMapping("/settings/pin")
    String pinForm(Model model) {
        User user = currentUser.require();
        Optional<FamilyMember> own = access.memberId(user).flatMap(members::findById);
        model.addAttribute("member", own.orElse(null));
        return "settings/pin";
    }

    @PostMapping("/settings/pin")
    String setOwnPin(@RequestParam(required = false) String pin, RedirectAttributes redirect) {
        User user = currentUser.require();
        Long memberId = access.memberId(user).orElseThrow(() ->
                new AccessDeniedException("Dein Konto ist mit keinem Familienmitglied verknüpft"));
        return setPin(memberId, pin, user, redirect, "redirect:/settings/pin");
    }

    @PostMapping("/admin/members/{id}/pin")
    String setMemberPin(@PathVariable Long id, @RequestParam(required = false) String pin, RedirectAttributes redirect) {
        return setPin(id, pin, currentUser.require(), redirect, "redirect:/admin/members");
    }

    private String setPin(Long memberId, String pin, User actor, RedirectAttributes redirect, String target) {
        try {
            pins.setPin(memberId, pin, actor);
            redirect.addFlashAttribute("success", pin == null || pin.isBlank()
                    ? "Die PIN wurde entfernt." : "Die PIN wurde gespeichert.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return target;
    }
}
