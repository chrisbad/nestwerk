package club.badernet.nestwerk.kitchen;

import club.badernet.nestwerk.kitchen.KitchenSettings.BringAccount;
import club.badernet.nestwerk.kitchen.integration.BringApi;
import club.badernet.nestwerk.kitchen.integration.BringException;
import club.badernet.nestwerk.kitchen.integration.BringList;
import club.badernet.nestwerk.user.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Einkaufsliste mit Bring! abgleichen. Ein Ausfall von Bring! endet in einer Meldung ({@code error}); was bis dahin
 * wirklich gesendet wurde, bleibt als gesendet markiert, sonst ändert sich nichts.
 */
@Service
public class BringSync {

    /** Ergebnis von Senden oder Abgleichen: Anzahl betroffener Artikel und gegebenenfalls die Fehlermeldung. */
    public record Result(int count, String error) {

        public boolean failed() {
            return error != null;
        }
    }

    private final ShoppingRepository shopping;
    private final KitchenSettings settings;
    private final BringApi api;
    private final KitchenAccess access;
    private final Clock clock;

    public BringSync(ShoppingRepository shopping, KitchenSettings settings, BringApi api, KitchenAccess access,
                     Clock clock) {
        this.shopping = shopping;
        this.settings = settings;
        this.api = api;
        this.access = access;
        this.clock = clock;
    }

    public boolean configured() {
        return settings.bring().ready();
    }

    /** Verbindet mit Bring! (Zugangsdaten testen und speichern) und liefert die Listen des Kontos. */
    @Transactional
    public List<BringList> connect(String email, String password, User actor) {
        access.require(actor, KitchenPermission.KITCHEN_SETTINGS);
        if (email == null || email.isBlank() || password == null || password.isEmpty()) {
            throw new IllegalArgumentException("Bitte E-Mail und Passwort von Bring! angeben.");
        }
        List<BringList> lists = api.lists(email.strip(), password);
        settings.saveBringLogin(email.strip(), password);
        if (lists.isEmpty()) {
            throw new BringException("Das Bring!-Konto hat keine Liste.");
        }
        BringAccount account = settings.bring();
        boolean known = lists.stream().anyMatch(l -> l.uuid().equals(account.listUuid()));
        if (!known) {
            settings.saveBringList(lists.getFirst().uuid(), lists.getFirst().name());
        }
        return lists;
    }

    /** Listen des gespeicherten Kontos, für die Auswahl in den Einstellungen. */
    public List<BringList> lists(User actor) {
        access.require(actor, KitchenPermission.KITCHEN_SETTINGS);
        BringAccount account = settings.bring();
        return api.lists(account.email(), account.password());
    }

    @Transactional
    public void chooseList(String uuid, User actor) {
        access.require(actor, KitchenPermission.KITCHEN_SETTINGS);
        BringList list = lists(actor).stream().filter(l -> l.uuid().equals(uuid)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Diese Liste gibt es bei Bring! nicht."));
        settings.saveBringList(list.uuid(), list.name());
    }

    @Transactional
    public void disconnect(User actor) {
        access.require(actor, KitchenPermission.KITCHEN_SETTINGS);
        settings.clearBring();
    }

    /** Sendet alle offenen Artikel an Bring! (Status {@code SENT}). */
    @Transactional
    public Result send(User actor) {
        access.require(actor, KitchenPermission.KITCHEN_SHOPPING);
        BringAccount account = settings.bring();
        if (!account.ready()) {
            return new Result(0, "Bring! ist noch nicht eingerichtet – das geht in den Einstellungen der Küche.");
        }
        int sent = 0;
        for (ShoppingItem item : shopping.findByStatusInOrderByCreatedAtAsc(EnumSet.of(ShoppingStatus.OPEN))) {
            try {
                api.add(account.email(), account.password(), account.listUuid(), item.getName(), item.specification());
            } catch (BringException e) {
                return new Result(sent, e.getMessage());
            }
            item.markSent(Instant.now(clock));
            sent++;
        }
        return new Result(sent, null);
    }

    /** Alles, was Nestwerk gesendet hat und bei Bring! nicht mehr „zu kaufen“ ist, gilt als gekauft. */
    @Transactional
    public Result sync(User actor) {
        access.require(actor, KitchenPermission.KITCHEN_SHOPPING);
        return sync();
    }

    /** Für den Zeitplaner; ohne eingerichtetes Bring! oder gesendete Artikel passiert nichts. */
    @Transactional
    public Result sync() {
        BringAccount account = settings.bring();
        if (!account.ready()) {
            return new Result(0, "Bring! ist noch nicht eingerichtet – das geht in den Einstellungen der Küche.");
        }
        List<ShoppingItem> sent = shopping.findByStatusInOrderByCreatedAtAsc(EnumSet.of(ShoppingStatus.SENT));
        if (sent.isEmpty()) {
            return new Result(0, null);
        }
        Set<String> open;
        try {
            open = api.open(account.email(), account.password(), account.listUuid()).stream()
                    .map(n -> n.toLowerCase(Locale.GERMAN)).collect(Collectors.toSet());
        } catch (BringException e) {
            return new Result(0, e.getMessage());
        }
        int bought = 0;
        for (ShoppingItem item : sent) {
            if (!open.contains(item.getName().toLowerCase(Locale.GERMAN))) {
                item.markBought(Instant.now(clock));
                bought++;
            }
        }
        return new Result(bought, null);
    }
}
