package club.badernet.nestwerk.transfer;

import club.badernet.nestwerk.calendar.EventNotFoundException;
import club.badernet.nestwerk.contact.ContactNotFoundException;
import club.badernet.nestwerk.todo.TodoConflictException;
import club.badernet.nestwerk.todo.TodoNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.DateTimeException;
import java.util.function.Supplier;

/**
 * Führt einen Import-Datensatz in einer eigenen Transaktion aus. In der Vorschau wird sie danach zurückgerollt,
 * beim echten Import bestätigt. Ein fehlerhafter Datensatz hält die anderen nicht auf.
 */
@Component
public class RowRunner {

    private static final Logger log = LoggerFactory.getLogger(RowRunner.class);

    private final TransactionTemplate tx;

    public RowRunner(PlatformTransactionManager transactions) {
        this.tx = new TransactionTemplate(transactions);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public ImportReport.Row run(String label, String detail, boolean commit, Supplier<ImportReport.Row> work) {
        try {
            return tx.execute(status -> {
                ImportReport.Row row = work.get();
                if (!commit || row.action() == ImportReport.Action.SKIP) {
                    status.setRollbackOnly();
                }
                return row;
            });
        } catch (AccessDeniedException e) {
            return ImportReport.Row.skip(label, detail, "keine Berechtigung");
        } catch (IllegalArgumentException | IllegalStateException | DateTimeException
                 | EventNotFoundException | ContactNotFoundException | TodoNotFoundException
                 | TodoConflictException e) {
            return ImportReport.Row.skip(label, detail, e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Import-Datensatz „{}“ fehlgeschlagen", label, e);
            return ImportReport.Row.skip(label, detail, "interner Fehler");
        }
    }
}
