# ToDo Stufe 5: Kanäle E-Mail und Push – Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans. Kompakter Plan; Ausführung inline durch den Autor.

**Goal:** Benachrichtigungen zusätzlich per E-Mail und Browser-Push zustellen; welche Kanäle gelten, legen Admin (Standard), Benutzer und einzelnes ToDo fest. Nestwerk wird als Web-App installierbar.

**Architecture:** `NotificationChannel`-Implementierungen `EmailChannel` und `PushChannel` legen beim Anlegen einer Benachrichtigung Versandaufträge in `notification_deliveries` an, wenn der Kanal eingerichtet ist und die aufgelöste Einstellung „an“ ist. `NotificationPreferences` löst auf: ToDo → Benutzer → global → Vorgabe (Push an, E-Mail aus). `DeliveryWorker` arbeitet den Postausgang alle 30 s ab (bis zu 5 Versuche, wachsende Abstände). E-Mail über `JavaMailSender`; Web-Push (RFC 8291 `aes128gcm`, VAPID RFC 8292) mit JDK-Kryptografie in `WebPushSender`, Schlüssel werden beim ersten Start erzeugt und in `app_settings` gespeichert.

**Spec:** `docs/superpowers/specs/2026-10-14-todo-design.md` (Kanäle, Einstellungsebenen, Konfiguration)

## Global Constraints

- Migration `V10__notification_channels.sql`: `users.email`, `notification_deliveries`, `notification_preferences`, `push_subscriptions`.
- Kanal nicht eingerichtet (kein `MAIL_HOST` bzw. kein Push-Schlüssel) → wird überall ausgeblendet, keine Aufträge.
- E-Mail nur an Benutzer mit hinterlegter Adresse; Tageslimit 20 Mails je Empfänger, darüber werden Aufträge übersprungen (Status `SKIPPED`).
- Push-Abos, die der Dienst mit 404/410 ablehnt, werden gelöscht.
- Links in Mails/Push sind absolut über `NESTWERK_BASE_URL` (Standard `http://localhost:8080`).
- `/sw.js` und `/manifest.webmanifest` sind ohne Anmeldung abrufbar.

## Rulings

- Web-Push wird ohne Fremdbibliothek mit JDK-Kryptografie umgesetzt statt mit `nl.martijndwars:web-push` (Spec) — die Bibliothek ist seit Jahren unverändert und zieht BouncyCastle und einen HTTP-Client nach; die Verschlüsselung ist durch einen Rundlauf-Test (Verschlüsseln → Entschlüsseln wie ein Browser) abgesichert — Kosten falls falsch: Push kommt nicht an, In-App bleibt unberührt.
- Statt einer Sammel-Mail über dem Tageslimit werden weitere Mails übersprungen — einfacher, In-App und Push bleiben vollständig — Kosten: an sehr aktiven Tagen fehlen Mails.

## Review Focus

- Benutzer ohne E-Mail-Adresse, E-Mail global an → kein Auftrag, kein Fehler (`noAddressNoMail`).
- Push-Dienst antwortet 410 → Abo gelöscht, Auftrag nicht wiederholt (`goneSubscriptionIsRemoved`).
- SMTP-Server nicht erreichbar → Wiederholversuche mit wachsendem Abstand, nach 5 Versuchen `FAILED` mit Fehlertext (`failedDeliveryIsRetriedThenGivenUp`).
- ToDo-Ausnahme „nie“ schlägt Benutzer „an“, Benutzer „aus“ schlägt global „an“ (`preferenceLevelsResolveFinestFirst`).
- Fremdes Push-Abo löschen per manipulierter ID → 404 (`foreignSubscriptionCannotBeRemoved`).

## Aufgaben

1. Datenmodell, `NotificationPreferences` (Lesen/Schreiben/Auflösen) mit Tests.
2. `EmailChannel`, `PushChannel`, `DeliveryWorker`, `MailSender`-Anbindung (GreenMail-Test) mit Tests.
3. `WebPushSender` (VAPID, Verschlüsselung, Versand) mit Rundlauf-Test und Mock-Endpunkt.
4. Oberfläche: `/settings/notifications` (E-Mail-Adresse, Matrix Standard/an/aus, Geräte, Test-Push), `/admin/notifications` (globale Matrix, Status, Test-Mail, Fehlerliste), ToDo-Ausnahme in der Seitenleiste, Service Worker, Manifest; Tests.
5. Gesamtprüfung, README/.env.example, Implementierungsliste.
