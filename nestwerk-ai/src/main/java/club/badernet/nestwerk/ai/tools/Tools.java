package club.badernet.nestwerk.ai.tools;

import club.badernet.nestwerk.ai.McpCaller;
import club.badernet.nestwerk.calendar.CategoryNotFoundException;
import club.badernet.nestwerk.calendar.EventNotFoundException;
import club.badernet.nestwerk.contact.ContactNotFoundException;
import club.badernet.nestwerk.household.ChoreConflictException;
import club.badernet.nestwerk.household.ChoreNotFoundException;
import club.badernet.nestwerk.kitchen.integration.ExternalServiceException;
import club.badernet.nestwerk.notification.NotificationNotFoundException;
import club.badernet.nestwerk.todo.TodoConflictException;
import club.badernet.nestwerk.todo.TodoNotFoundException;
import club.badernet.nestwerk.user.User;
import io.modelcontextprotocol.common.McpTransportContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.function.Function;

/**
 * Gemeinsamer Rahmen der MCP-Tools: ermittelt den Benutzer, läuft in einer Transaktion (damit Lazy-Felder für die
 * Antwort lesbar sind) und übersetzt Fehler der Services in verständliche Meldungen für die KI.
 */
@Component
public class Tools {

    /** Fehler, den die KI als Tool-Ergebnis mit {@code isError} sieht. */
    public static class ToolException extends RuntimeException {
        public ToolException(String message) {
            super(message);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(Tools.class);

    private final McpCaller caller;
    private final TransactionTemplate readTx;
    private final TransactionTemplate writeTx;

    public Tools(McpCaller caller, PlatformTransactionManager transactions) {
        this.caller = caller;
        this.writeTx = new TransactionTemplate(transactions);
        this.readTx = new TransactionTemplate(transactions);
        this.readTx.setReadOnly(true);
    }

    public <T> T read(McpTransportContext context, Function<User, T> work) {
        return run(readTx, context, work);
    }

    public <T> T write(McpTransportContext context, Function<User, T> work) {
        return run(writeTx, context, work);
    }

    private <T> T run(TransactionTemplate tx, McpTransportContext context, Function<User, T> work) {
        try {
            return tx.execute(status -> work.apply(caller.require(context)));
        } catch (ToolException e) {
            throw e;
        } catch (AccessDeniedException e) {
            throw new ToolException("Keine Berechtigung: " + e.getMessage());
        } catch (IllegalArgumentException | IllegalStateException | DateTimeException e) {
            throw new ToolException(e.getMessage());
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new ToolException("Der Eintrag wurde inzwischen geändert. Bitte neu laden und erneut versuchen.");
        } catch (TodoNotFoundException | EventNotFoundException | CategoryNotFoundException | ChoreNotFoundException
                 | ContactNotFoundException
                 | NotificationNotFoundException | TodoConflictException | ChoreConflictException
                 | NoSuchElementException | ExternalServiceException e) {
            throw new ToolException(e.getMessage());
        } catch (RuntimeException e) {
            log.warn("MCP-Tool fehlgeschlagen", e);
            throw new ToolException("Interner Fehler. Details stehen im Server-Log.");
        }
    }

    static LocalDate date(String value, String name) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new ToolException(name + ": Datum im Format JJJJ-MM-TT erwartet, nicht „" + value + "“.");
        }
    }

    static LocalTime time(String value, String name) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalTime.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new ToolException(name + ": Uhrzeit im Format HH:MM erwartet, nicht „" + value + "“.");
        }
    }

    static <E extends Enum<E>> E enumValue(Class<E> type, String value, String name) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ToolException(name + ": erlaubt sind " + Arrays.toString(type.getEnumConstants()) + ".");
        }
    }

    static <T> T required(T value, String name) {
        if (value == null || value instanceof String s && s.isBlank()) {
            throw new ToolException("Angabe fehlt: " + name);
        }
        return value;
    }
}
