package club.badernet.nestwerk.ai.tools;

import club.badernet.nestwerk.backup.BackupRun;
import club.badernet.nestwerk.backup.BackupScheduler;
import club.badernet.nestwerk.backup.BackupService;
import club.badernet.nestwerk.backup.BackupSettings;
import club.badernet.nestwerk.security.AdminPermission;
import club.badernet.nestwerk.update.AvailableUpdate;
import club.badernet.nestwerk.update.Channel;
import club.badernet.nestwerk.update.UpdateCheck;
import club.badernet.nestwerk.update.UpdateJobs;
import club.badernet.nestwerk.update.UpdateRun;
import club.badernet.nestwerk.update.UpdateSettings;
import io.modelcontextprotocol.common.McpTransportContext;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Betrieb: Datensicherung und Updates. Zugangsdaten für externe Ziele, Verschlüsselungs-Passphrase,
 * Wiederherstellen, Löschen sowie Hoch- und Herunterladen von Sicherungen bleiben der Weboberfläche vorbehalten.
 */
@Component
public class SystemTools {

    public record BackupFileItem(String name, long sizeBytes, String modified) {
    }

    public record BackupRunItem(Long id, String kind, String status, String started, String finished, String file,
                                Long sizeBytes, String message) {
    }

    public record BackupStatus(boolean busy, boolean scheduleEnabled, String frequency, String weekday, String time,
                               String nextRun, int keepLast, int keepWeekly, int keepMonthly, boolean notifySuccess,
                               boolean encrypted, boolean externalCopy, List<BackupFileItem> files,
                               List<BackupRunItem> recentRuns) {
    }

    public record UpdateRunItem(String jobId, String from, String to, String status, String step, String message,
                                String requested) {
    }

    public record UpdateStatus(String currentVersion, boolean devBuild, String channel, boolean updaterInstalled,
                               String lastChecked, String lastError, String availableVersion, String releaseNotes,
                               UpdateRunItem openRun, List<UpdateRunItem> recentRuns) {
    }

    private final Tools tools;
    private final AdminGuard guard;
    private final BackupService backups;
    private final BackupSettings backupSettings;
    private final UpdateCheck updateCheck;
    private final UpdateSettings updateSettings;
    private final UpdateJobs updateJobs;
    private final Clock clock;

    public SystemTools(Tools tools, AdminGuard guard, BackupService backups, BackupSettings backupSettings,
                       UpdateCheck updateCheck, UpdateSettings updateSettings, UpdateJobs updateJobs, Clock clock) {
        this.tools = tools;
        this.guard = guard;
        this.backups = backups;
        this.backupSettings = backupSettings;
        this.updateCheck = updateCheck;
        this.updateSettings = updateSettings;
        this.updateJobs = updateJobs;
        this.clock = clock;
    }

    // --- Datensicherung ------------------------------------------------------------------

    @McpTool(name = "backup_status", description = "Betrieb: Zeigt Zeitplan, Aufbewahrung, vorhandene Sicherungen und "
            + "die letzten Läufe. Braucht BACKUP_MANAGE.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public BackupStatus backupStatus(McpTransportContext context) {
        return tools.read(context, user -> {
            guard.require(user, AdminPermission.BACKUP_MANAGE);
            return status(user);
        });
    }

    @McpTool(name = "start_backup", description = "Betrieb: Startet sofort eine Datensicherung (läuft im "
            + "Hintergrund; Ergebnis in backup_status). Braucht BACKUP_MANAGE.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false))
    public String startBackup(McpTransportContext context) {
        return tools.write(context, user -> {
            guard.require(user, AdminPermission.BACKUP_MANAGE);
            backups.startBackup(user);
            return "Die Sicherung läuft. backup_status zeigt, wann sie fertig ist.";
        });
    }

    @McpTool(name = "set_backup_schedule", description = "Betrieb: Stellt den automatischen Sicherungsplan ein. "
            + "frequency DAILY oder WEEKLY, weekday (bei WEEKLY) z. B. SUNDAY, time HH:MM. Braucht BACKUP_MANAGE.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false,
                    idempotentHint = true))
    public BackupStatus setBackupSchedule(McpTransportContext context,
                                          @McpToolParam(description = "Automatische Sicherung an/aus")
                                          Boolean enabled,
                                          @McpToolParam(description = "DAILY oder WEEKLY", required = false)
                                          String frequency,
                                          @McpToolParam(description = "Wochentag, z. B. SUNDAY", required = false)
                                          String weekday,
                                          @McpToolParam(description = "Uhrzeit HH:MM", required = false) String time) {
        return tools.write(context, user -> {
            guard.require(user, AdminPermission.BACKUP_MANAGE);
            BackupSettings.Schedule current = backupSettings.schedule();
            BackupSettings.Frequency f = Tools.enumValue(BackupSettings.Frequency.class, frequency, "frequency");
            DayOfWeek day = Tools.enumValue(DayOfWeek.class, weekday, "weekday");
            backupSettings.updateSchedule(new BackupSettings.Schedule(Tools.required(enabled, "enabled"),
                    f != null ? f : current.frequency(), day != null ? day : current.weekday(),
                    time != null && !time.isBlank() ? Tools.time(time, "time") : current.time()), clock.instant());
            return status(user);
        });
    }

    @McpTool(name = "set_backup_retention", description = "Betrieb: Wie viele Sicherungen bleiben: die letzten "
            + "keepLast, dazu je eine pro Woche (keepWeekly) und pro Monat (keepMonthly). Nicht angegebene Werte "
            + "bleiben. Braucht BACKUP_MANAGE.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false,
                    idempotentHint = true))
    public BackupStatus setBackupRetention(McpTransportContext context,
                                           @McpToolParam(description = "Letzte Sicherungen", required = false)
                                           Integer keepLast,
                                           @McpToolParam(description = "Wöchentliche Sicherungen", required = false)
                                           Integer keepWeekly,
                                           @McpToolParam(description = "Monatliche Sicherungen", required = false)
                                           Integer keepMonthly,
                                           @McpToolParam(description = "Bei Erfolg benachrichtigen", required = false)
                                           Boolean notifySuccess) {
        return tools.write(context, user -> {
            guard.require(user, AdminPermission.BACKUP_MANAGE);
            BackupSettings.RetentionRule current = backupSettings.retention();
            backupSettings.updateRetention(new BackupSettings.RetentionRule(
                    keepLast != null ? keepLast : current.keepLast(),
                    keepWeekly != null ? keepWeekly : current.keepWeekly(),
                    keepMonthly != null ? keepMonthly : current.keepMonthly()),
                    notifySuccess != null ? notifySuccess : backupSettings.notifySuccess());
            return status(user);
        });
    }

    // --- Updates ---------------------------------------------------------------------------

    @McpTool(name = "update_status", description = "Betrieb: Zeigt die laufende Version, den Update-Kanal, eine "
            + "verfügbare neuere Version mit Änderungen und den Verlauf. Braucht UPDATE_MANAGE.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public UpdateStatus updateStatus(McpTransportContext context) {
        return tools.read(context, user -> {
            guard.require(user, AdminPermission.UPDATE_MANAGE);
            return updateState();
        });
    }

    @McpTool(name = "check_for_updates", description = "Betrieb: Fragt jetzt nach einer neueren Version. Braucht "
            + "UPDATE_MANAGE.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = true))
    public UpdateStatus checkForUpdates(McpTransportContext context) {
        return tools.write(context, user -> {
            guard.require(user, AdminPermission.UPDATE_MANAGE);
            UpdateCheck.CheckResult result = updateCheck.check();
            if (result.error() != null) {
                throw new Tools.ToolException(result.error());
            }
            return updateState();
        });
    }

    @McpTool(name = "set_update_channel", description = "Betrieb: Wählt den Update-Kanal (RELEASES = nur "
            + "Veröffentlichungen, sonst siehe update_status). Den Zugriffstoken setzt man in der Weboberfläche. "
            + "Braucht UPDATE_MANAGE.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false,
                    idempotentHint = true))
    public UpdateStatus setUpdateChannel(McpTransportContext context,
                                         @McpToolParam(description = "Kanal, z. B. RELEASES") String channel) {
        return tools.write(context, user -> {
            guard.require(user, AdminPermission.UPDATE_MANAGE);
            updateSettings.update(Tools.required(Tools.enumValue(Channel.class, channel, "channel"), "channel"),
                    null);
            return updateState();
        });
    }

    @McpTool(name = "start_update", description = "Betrieb: Spielt die verfügbare neuere Version ein. ACHTUNG: "
            + "Nestwerk wird dabei neu gestartet und ist kurz nicht erreichbar. Nur nach ausdrücklicher Zustimmung "
            + "des Benutzers mit confirm=true aufrufen. Braucht UPDATE_MANAGE.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, openWorldHint = true))
    public UpdateStatus startUpdate(McpTransportContext context,
                                    @McpToolParam(description = "Muss true sein: der Benutzer hat zugestimmt")
                                    Boolean confirm) {
        return tools.write(context, user -> {
            guard.require(user, AdminPermission.UPDATE_MANAGE);
            if (!Boolean.TRUE.equals(confirm)) {
                throw new Tools.ToolException("Bitte zuerst den Benutzer fragen und dann confirm=true setzen: "
                        + "Nestwerk startet beim Update neu.");
            }
            AvailableUpdate update = updateCheck.cached()
                    .orElseThrow(() -> new Tools.ToolException("Es ist keine neuere Version bekannt."));
            updateJobs.start(update, updateSettings.channel(), user);
            return updateState();
        });
    }

    // --- Hilfen ------------------------------------------------------------------------------

    private BackupStatus status(club.badernet.nestwerk.user.User user) {
        BackupSettings.Schedule schedule = backupSettings.schedule();
        BackupSettings.RetentionRule retention = backupSettings.retention();
        return new BackupStatus(backups.isBusy(), schedule.enabled(), schedule.frequency().name(),
                schedule.weekday() == null ? null : schedule.weekday().name(),
                schedule.time() == null ? null : schedule.time().toString(),
                schedule.enabled() ? String.valueOf(BackupScheduler.nextSlot(schedule,
                        ZonedDateTime.now(clock)).toInstant()) : null,
                retention.keepLast(), retention.keepWeekly(), retention.keepMonthly(),
                backupSettings.notifySuccess(), backupSettings.encryptionEnabled(), backupSettings.externalEnabled(),
                backups.files(user).stream().map(f -> new BackupFileItem(f.name(), f.size(),
                        String.valueOf(f.modified()))).toList(),
                backups.recentRuns(user, 10).stream().map(SystemTools::run).toList());
    }

    private static BackupRunItem run(BackupRun r) {
        return new BackupRunItem(r.getId(), r.getKind().name(), r.getStatus().name(),
                String.valueOf(r.getStartedAt()), r.getFinishedAt() == null ? null : r.getFinishedAt().toString(),
                r.getFileName(), r.getSizeBytes(), r.getMessage());
    }

    private UpdateStatus updateState() {
        Optional<AvailableUpdate> available = updateCheck.cached();
        return new UpdateStatus(updateCheck.current().raw(), updateCheck.current().isDev(),
                updateSettings.channel().name(), updateJobs.updaterInstalled(),
                updateSettings.lastChecked().map(String::valueOf).orElse(null),
                updateSettings.lastError().orElse(null), available.map(AvailableUpdate::version).orElse(null),
                available.map(AvailableUpdate::notes).orElse(null), updateJobs.open().map(SystemTools::updateRun)
                .orElse(null), updateJobs.recent(10).stream().map(SystemTools::updateRun).toList());
    }

    private static UpdateRunItem updateRun(UpdateRun r) {
        return new UpdateRunItem(r.getJobId(), r.getFromVersion(), r.getToVersion(), r.getStatus().name(),
                r.getStep(), r.getMessage(), String.valueOf(r.getRequestedAt()));
    }
}
