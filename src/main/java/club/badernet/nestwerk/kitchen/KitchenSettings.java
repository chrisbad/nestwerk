package club.badernet.nestwerk.kitchen;

import club.badernet.nestwerk.backup.SecretBox;
import club.badernet.nestwerk.notification.AppSettings;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;


/** Bring!-Zugang und gewählte Liste, gespeichert in {@code app_settings}; das Passwort verschlüsselt. */
@Component
public class KitchenSettings {

    /** Gespeicherter Zugang; {@code password} ist leer, wenn keines gespeichert oder nicht entschlüsselbar ist. */
    public record BringAccount(String email, String password, String listUuid, String listName) {

        public boolean configured() {
            return email != null && !email.isBlank() && password != null && !password.isEmpty();
        }

        public boolean ready() {
            return configured() && listUuid != null && !listUuid.isBlank();
        }
    }

    static final String EMAIL = "kitchen.bring.email";
    static final String PASSWORD = "kitchen.bring.password";
    static final String LIST_UUID = "kitchen.bring.listUuid";
    static final String LIST_NAME = "kitchen.bring.listName";

    private final AppSettings settings;
    private final SecretBox secrets;

    public KitchenSettings(AppSettings settings, SecretBox secrets) {
        this.settings = settings;
        this.secrets = secrets;
    }

    public BringAccount bring() {
        return new BringAccount(settings.get(EMAIL).orElse(null),
                settings.get(PASSWORD).map(secrets::decrypt).orElse(null),
                settings.get(LIST_UUID).orElse(null), settings.get(LIST_NAME).orElse(null));
    }

    @Transactional
    public void saveBringLogin(String email, String password) {
        settings.set(EMAIL, email);
        settings.set(PASSWORD, secrets.encrypt(password));
    }

    @Transactional
    public void saveBringList(String uuid, String name) {
        settings.set(LIST_UUID, uuid);
        settings.set(LIST_NAME, name);
    }

    @Transactional
    public void clearBring() {
        for (String key : new String[]{EMAIL, PASSWORD, LIST_UUID, LIST_NAME}) {
            settings.set(key, "");
        }
    }

}
