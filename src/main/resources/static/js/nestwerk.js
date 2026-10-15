/* Nestwerk – kleine, abhängigkeitsfreie UI-Helfer. htmx wird separat geladen. */
(() => {
    "use strict";

    const root = document.documentElement;

    /* --- Farbschema: System → Hell → Dunkel ------------------------------ */

    const THEME_KEY = "nestwerk-theme";
    const THEME_LABELS = { system: "System", light: "Hell", dark: "Dunkel" };

    function readTheme() {
        try {
            const value = localStorage.getItem(THEME_KEY);
            return value === "light" || value === "dark" ? value : "system";
        } catch (e) {
            return "system";
        }
    }

    function applyTheme(mode) {
        root.dataset.themeMode = mode;
        if (mode === "system") delete root.dataset.theme;
        else root.dataset.theme = mode;
        document.querySelectorAll("[data-theme-label]").forEach(el => {
            el.textContent = "Farbschema: " + THEME_LABELS[mode];
        });
        document.querySelectorAll("[data-theme-toggle]").forEach(el => {
            el.title = "Farbschema: " + THEME_LABELS[mode];
        });
    }

    function initTheme() {
        applyTheme(readTheme());
        document.addEventListener("click", event => {
            if (!event.target.closest("[data-theme-toggle]")) return;
            const order = ["system", "light", "dark"];
            const next = order[(order.indexOf(root.dataset.themeMode) + 1) % order.length];
            try {
                if (next === "system") localStorage.removeItem(THEME_KEY);
                else localStorage.setItem(THEME_KEY, next);
            } catch (e) { /* nur für diese Sitzung */ }
            applyTheme(next);
        });
    }

    /* --- Navigation und Benutzermenü ------------------------------------- */

    function initNav() {
        const toggle = document.querySelector("[data-nav-toggle]");
        const links = document.getElementById("main-nav");
        if (toggle && links) {
            toggle.addEventListener("click", () => {
                const open = links.classList.toggle("is-open");
                toggle.setAttribute("aria-expanded", String(open));
            });
        }

        document.querySelectorAll("[data-menu]").forEach(menu => {
            const button = menu.querySelector("[data-menu-toggle]");
            const panel = menu.querySelector("[data-menu-panel]");
            const setOpen = open => {
                panel.hidden = !open;
                button.setAttribute("aria-expanded", String(open));
                if (open) panel.querySelector("button, a")?.focus();
            };
            button.addEventListener("click", () => setOpen(panel.hidden));
            document.addEventListener("click", event => {
                if (!panel.hidden && !menu.contains(event.target)) setOpen(false);
            });
            menu.addEventListener("keydown", event => {
                if (event.key === "Escape" && !panel.hidden) {
                    setOpen(false);
                    button.focus();
                }
            });
        });
    }

    /* --- Dialoge --------------------------------------------------------- */

    function initDialogs() {
        document.addEventListener("click", event => {
            const opener = event.target.closest("[data-open-dialog]");
            if (opener) {
                const dialog = document.getElementById(opener.dataset.openDialog);
                if (dialog) refreshBookNow(dialog);
                dialog?.showModal();
                dialog?.querySelector("input:not([type=hidden])")?.focus();
                return;
            }
            const closer = event.target.closest("[data-close-dialog]");
            if (closer) {
                closer.closest("dialog")?.close();
                return;
            }
            // Klick auf den Hintergrund schließt den Dialog
            if (event.target instanceof HTMLDialogElement && event.target.open) {
                const box = event.target.getBoundingClientRect();
                const inside = event.clientX >= box.left && event.clientX <= box.right
                    && event.clientY >= box.top && event.clientY <= box.bottom;
                if (!inside) event.target.close();
            }
        });
    }

    /* --- Bestätigung vor destruktiven Aktionen ----------------------------- */

    function initConfirm() {
        const dialog = document.getElementById("confirm-dialog");
        if (!dialog) return;
        const ok = dialog.querySelector("[data-confirm-ok]");
        const defaultLabel = ok.textContent;
        let onConfirm = null;
        const ask = (form, proceed) => {
            onConfirm = proceed;
            dialog.querySelector("[data-confirm-text]").textContent = form.dataset.confirm;
            ok.textContent = form.dataset.confirmLabel || defaultLabel;
            dialog.showModal();
            ok.focus();
        };
        // Klassische Formulare
        document.addEventListener("submit", event => {
            const form = event.target;
            if (!form.matches("form[data-confirm]") || form.hasAttribute("hx-post")
                || form.dataset.confirmed === "true") return;
            event.preventDefault();
            ask(form, () => {
                form.dataset.confirmed = "true";
                form.requestSubmit();
            });
        });
        // htmx-Formulare: htmx fragt vor jeder Anfrage nach
        document.addEventListener("htmx:confirm", event => {
            const form = event.detail.elt;
            if (!form.matches || !form.matches("form[data-confirm]")) return;
            event.preventDefault();
            ask(form, () => event.detail.issueRequest(true));
        });
        ok.addEventListener("click", () => {
            const proceed = onConfirm;
            onConfirm = null;
            dialog.close();
            if (proceed) proceed();
        });
        dialog.addEventListener("close", () => { onConfirm = null; });
    }

    /* --- Passwort anzeigen ----------------------------------------------- */

    function initPasswordToggles() {
        document.addEventListener("click", event => {
            const button = event.target.closest("[data-password-toggle]");
            if (!button) return;
            const wrap = button.closest(".password");
            const input = wrap.querySelector("input");
            const visible = input.type === "password";
            input.type = visible ? "text" : "password";
            wrap.classList.toggle("is-visible", visible);
            button.setAttribute("aria-pressed", String(visible));
            button.querySelector(".visually-hidden").textContent =
                visible ? "Passwort verbergen" : "Passwort anzeigen";
        });
    }

    /* --- In die Zwischenablage kopieren ---------------------------------- */

    function initCopyButtons() {
        document.addEventListener("click", event => {
            const button = event.target.closest("[data-copy]");
            if (!button) return;
            const source = document.getElementById(button.dataset.copy);
            if (!source) return;
            const text = "value" in source ? source.value : source.textContent;
            navigator.clipboard.writeText(text.trim()).then(
                () => showToast("Kopiert.", "success"),
                () => showToast("Kopieren ging nicht. Bitte markieren und selbst kopieren.", "error"));
        });
    }

    /* --- Große Dateien als Datenstrom hochladen (Sicherungen) ----------- */

    function initRawUploads() {
        document.addEventListener("submit", event => {
            const form = event.target;
            if (!form.matches("form[data-raw-upload]")) return;
            event.preventDefault();
            const file = form.querySelector('input[type="file"]').files[0];
            if (!file) return;
            const progress = form.querySelector("[data-upload-progress]");
            const button = form.querySelector('button[type="submit"]');
            const xhr = new XMLHttpRequest();
            xhr.open("POST", form.dataset.uploadUrl);
            xhr.setRequestHeader("Content-Type", "application/octet-stream");
            const token = document.querySelector('meta[name="_csrf"]')?.content;
            const header = document.querySelector('meta[name="_csrf_header"]')?.content;
            if (token && header) xhr.setRequestHeader(header, token);
            xhr.upload.addEventListener("progress", e => {
                if (e.lengthComputable) progress.value = Math.round(e.loaded * 100 / e.total);
            });
            xhr.addEventListener("load", () => {
                button.disabled = false;
                progress.hidden = true;
                let body = {};
                try { body = JSON.parse(xhr.responseText); } catch (ignored) { /* kein JSON */ }
                if (xhr.status === 200) {
                    window.location.reload();
                } else {
                    showToast(body.error || "Hochladen fehlgeschlagen.", "error");
                }
            });
            xhr.addEventListener("error", () => {
                button.disabled = false;
                progress.hidden = true;
                showToast("Keine Verbindung zum Server. Prüfe die Netzwerkverbindung.", "error");
            });
            button.disabled = true;
            progress.hidden = false;
            progress.value = 0;
            xhr.send(file);
        });
    }

    /* --- Tabellen-Schnellsuche ------------------------------------------- */

    function applyFilter(input) {
        const table = document.getElementById(input.dataset.filterTable);
        if (!table) return;
        const query = input.value.trim().toLocaleLowerCase("de");
        let visible = 0;
        table.querySelectorAll("tbody tr").forEach(row => {
            const match = row.textContent.toLocaleLowerCase("de").includes(query);
            row.hidden = !match;
            if (match) visible++;
        });
        const empty = document.querySelector(`[data-filter-empty="${table.id}"]`);
        if (empty) empty.hidden = visible > 0;
    }

    function initFilters() {
        // Der Browser stellt Suchtext nach Neuladen teils wieder her
        window.addEventListener("pageshow", () => {
            document.querySelectorAll("[data-filter-table]").forEach(applyFilter);
        });
        document.addEventListener("input", event => {
            if (event.target.matches("[data-filter-table]")) applyFilter(event.target);
        });
    }

    /* --- Toasts ---------------------------------------------------------- */

    function showToast(message, kind) {
        const region = document.querySelector("[data-toasts]");
        if (!region || !message) return;
        const toast = document.createElement("div");
        toast.className = "toast" + (kind === "error" ? " toast-error" : "");
        toast.setAttribute("role", kind === "error" ? "alert" : "status");
        toast.textContent = message;
        region.append(toast);
        setTimeout(() => {
            toast.classList.add("is-leaving");
            toast.addEventListener("animationend", () => toast.remove(), { once: true });
            setTimeout(() => toast.remove(), 400);
        }, kind === "error" ? 6000 : 3500);
    }

    function consumeToasts(scope) {
        scope.querySelectorAll("[data-toast]").forEach(el => {
            showToast(el.textContent.trim(), el.dataset.toast);
            el.remove();
        });
    }

    /* --- htmx-Anbindung -------------------------------------------------- */

    function initHtmx() {
        const token = document.querySelector('meta[name="_csrf"]')?.content;
        const header = document.querySelector('meta[name="_csrf_header"]')?.content;

        document.addEventListener("htmx:configRequest", event => {
            if (token && header) event.detail.headers[header] = token;
        });

        document.addEventListener("htmx:afterSwap", event => {
            const error = document.querySelector("[data-form-error]");

            // Formular im Dialog: bei Erfolg schließen, bei Fehler Meldung im Dialog statt als Toast
            const form = event.detail.requestConfig?.elt?.closest?.("form");
            const dialog = form?.closest("dialog");
            if (dialog) {
                const slot = dialog.querySelector("[data-dialog-error]");
                if (error && slot) {
                    slot.textContent = error.textContent.trim();
                    slot.hidden = false;
                    error.remove();
                } else if (!error) {
                    form.reset();
                    form.querySelectorAll(".password.is-visible [data-password-toggle]").forEach(b => b.click());
                    if (slot) slot.hidden = true;
                    dialog.close();
                }
            }

            consumeToasts(document);
            document.querySelectorAll("[data-filter-table]").forEach(applyFilter);
        });

        document.addEventListener("htmx:responseError", () => {
            showToast("Die Aktion konnte nicht ausgeführt werden. Bitte lade die Seite neu und versuche es erneut.", "error");
        });
        document.addEventListener("htmx:sendError", () => {
            showToast("Keine Verbindung zum Server. Prüfe die Netzwerkverbindung.", "error");
        });
    }

    /* --- Terminformular: Ganztägig und Wiederholung ------------------------ */

    function initEventForm() {
        const form = document.querySelector("[data-event-form]");
        if (!form) return;

        const allDay = form.querySelector("[data-allday-toggle]");
        const timeFields = form.querySelectorAll("[data-time-field]");
        const syncAllDay = () => timeFields.forEach(f => {
            f.hidden = allDay.checked;
            f.disabled = allDay.checked;
        });
        allDay?.addEventListener("change", syncAllDay);
        if (allDay) syncAllDay();

        // Beginn verschoben: Ende mitziehen, wenn es sonst davor läge
        const startDate = form.querySelector("#startDate");
        const endDate = form.querySelector("#endDate");
        startDate?.addEventListener("change", () => {
            if (endDate.value && endDate.value < startDate.value) endDate.value = startDate.value;
        });

        const select = form.querySelector("[data-repeat-select]");
        const input = form.querySelector("[data-rrule-input]");
        const hint = form.querySelector("[data-rrule-hint]");
        if (!select || !input) return;
        const presets = [...select.options].map(o => o.value).filter(v => v && v !== "custom");
        const showCustom = custom => {
            input.hidden = !custom;
            if (hint) hint.hidden = !custom;
        };
        const current = input.value.trim().replace(/^RRULE:/i, "");
        if (!current) select.value = "";
        else if (presets.includes(current)) select.value = current;
        else select.value = "custom";
        showCustom(select.value === "custom");
        select.addEventListener("change", () => {
            if (select.value === "custom") {
                showCustom(true);
                input.focus();
            } else {
                input.value = select.value;
                showCustom(false);
            }
        });
    }

    /* --- Buchen: Minuten der gewählten Stufe übernehmen, Zeitpunkt aktuell halten --- */

    const pad = n => String(n).padStart(2, "0");

    function localNow() {
        const d = new Date();
        return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
    }

    // Die Seite kann lange offen sein (Tablet): beim Öffnen „jetzt“ neu setzen, solange nichts geändert wurde
    function refreshBookNow(root) {
        root.querySelectorAll("[data-book-now]").forEach(input => {
            if (!input.dataset.touched) input.value = localNow();
        });
    }

    function initBookForms() {
        document.addEventListener("input", event => {
            if (event.target.matches("[data-book-minutes], [data-book-now]")) event.target.dataset.touched = "1";
        });
        document.addEventListener("change", event => {
            const radio = event.target.closest("[data-book-form] input[name=effort]");
            if (!radio) return;
            const minutes = radio.closest("[data-book-form]").querySelector("[data-book-minutes]");
            if (minutes && !minutes.dataset.touched) minutes.value = radio.dataset.minutes;
        });
        // in der Capture-Phase, damit auch htmx schon den aktuellen Zeitpunkt sendet
        document.addEventListener("submit", event => {
            if (event.target.querySelector("[data-book-form]")) refreshBookNow(event.target);
        }, true);
    }

    /* --- Hausarbeit-Formular: Abschnitte je Art, Reihenfolge der Mitglieder --- */

    function initChoreForm() {
        const form = document.querySelector("[data-chore-form]");
        if (!form) return;
        const sections = form.querySelectorAll("[data-kind-section]");
        const syncKind = () => {
            const kind = form.querySelector("[data-chore-kind]:checked")?.value;
            sections.forEach(s => { s.hidden = !s.dataset.kindSection.split(" ").includes(kind); });
        };
        form.querySelectorAll("[data-chore-kind]").forEach(r => r.addEventListener("change", syncKind));
        syncKind();

        // Reihenfolge für „reihum“: in der Reihenfolge des Antippens
        const picker = form.querySelector("[data-member-picker]");
        if (!picker) return;
        const boxes = [...picker.querySelectorAll("[data-member-order]")];
        let order = (picker.dataset.initialOrder || "").split(",").filter(Boolean);
        boxes.forEach(b => { if (b.checked && !order.includes(b.value)) order.push(b.value); });
        order = order.filter(id => boxes.some(b => b.value === id && b.checked));
        const render = () => boxes.forEach(b => {
            const badge = b.parentElement.querySelector(".order-badge");
            if (badge) badge.textContent = b.checked ? String(order.indexOf(b.value) + 1) : "";
        });
        boxes.forEach(b => b.addEventListener("change", () => {
            order = order.filter(id => id !== b.value);
            if (b.checked) order.push(b.value);
            render();
        }));
        render();
        form.addEventListener("submit", () => {
            boxes.forEach(b => b.removeAttribute("name"));
            order.forEach(id => {
                const hidden = document.createElement("input");
                hidden.type = "hidden";
                hidden.name = "memberIds";
                hidden.value = id;
                form.appendChild(hidden);
            });
        });
    }

    /* --- ToDo-Board: Karten ziehen ändert den Status ------------------------ */

    function initBoard() {
        const board = document.querySelector("[data-board]");
        if (!board || !window.Sortable) return;
        const token = document.querySelector('meta[name="_csrf"]')?.content;
        const header = document.querySelector('meta[name="_csrf_header"]')?.content;
        const updateCounts = () => board.querySelectorAll(".board-column").forEach(col => {
            const count = col.querySelector(".count");
            const list = col.querySelector("[data-board-column]");
            if (count && list) count.textContent = list.children.length;
        });
        board.querySelectorAll("[data-board-column]").forEach(list => {
            window.Sortable.create(list, {
                group: "board",
                animation: 150,
                delay: 200,
                delayOnTouchOnly: true,
                filter: "select, option, a, button, input",
                preventOnFilter: false,
                onEnd: async evt => {
                    if (evt.from === evt.to) return;
                    const card = evt.item;
                    const status = evt.to.dataset.boardColumn;
                    const headers = { "HX-Request": "true", "Content-Type": "application/x-www-form-urlencoded" };
                    if (token && header) headers[header] = token;
                    try {
                        const response = await fetch(`/todos/${card.dataset.todoId}/status`, {
                            method: "POST", headers, body: new URLSearchParams({ status })
                        });
                        if (!response.ok) {
                            throw new Error((await response.text()) || "Der Status konnte nicht geändert werden.");
                        }
                        const select = card.querySelector("select[name=status]");
                        if (select) select.value = status;
                    } catch (error) {
                        evt.from.insertBefore(card, evt.from.children[evt.oldIndex] || null);
                        showToast(error.message, "error");
                    }
                    updateCounts();
                }
            });
        });
    }

    /* --- Kommentare und Anhänge ------------------------------------------- */

    function initCollaboration() {
        // @name per Klick in den Kommentar einfügen
        document.addEventListener("click", event => {
            const chip = event.target.closest("[data-mention]");
            if (!chip) return;
            const area = chip.closest("form")?.querySelector("[data-mention-target]");
            if (!area) return;
            const insert = "@" + chip.dataset.mention + " ";
            const pos = area.selectionStart ?? area.value.length;
            const before = area.value.slice(0, pos);
            const spacer = before && !/\s$/.test(before) ? " " : "";
            area.value = before + spacer + insert + area.value.slice(pos);
            area.focus();
            area.selectionStart = area.selectionEnd = pos + spacer.length + insert.length;
        });
        // Anzahl gewählter Dateien im Kommentarformular anzeigen
        document.addEventListener("change", event => {
            const input = event.target;
            if (!input.matches?.("input[type=file]")) return;
            const counter = input.closest("label")?.querySelector("[data-file-count]");
            if (counter) {
                counter.textContent = input.files.length === 0 ? "Dateien anhängen"
                    : input.files.length === 1 ? input.files[0].name : input.files.length + " Dateien";
            }
            const zone = input.closest("[data-dropzone]");
            if (zone && input.files.length > 0) zone.requestSubmit();
        });
        // Dateien in die Ablagefläche ziehen
        document.querySelectorAll("[data-dropzone]").forEach(zone => {
            const input = zone.querySelector("input[type=file]");
            ["dragenter", "dragover"].forEach(type => zone.addEventListener(type, event => {
                event.preventDefault();
                zone.classList.add("is-over");
            }));
            ["dragleave", "drop"].forEach(type => zone.addEventListener(type, () => zone.classList.remove("is-over")));
            zone.addEventListener("drop", event => {
                event.preventDefault();
                if (!event.dataTransfer?.files?.length) return;
                input.files = event.dataTransfer.files;
                zone.requestSubmit();
            });
        });
    }

    /* --- Kalender: fällige ToDos ein- und ausblenden ------------------------- */

    function initCalendarTodos() {
        const KEY = "nestwerk-cal-todos";
        const apply = () => {
            const calendar = document.getElementById("calendar");
            const toggle = document.querySelector("[data-toggle-todos]");
            if (!calendar || !toggle) return;
            let hidden = false;
            try { hidden = localStorage.getItem(KEY) === "hidden"; } catch (e) { /* ohne Speicher */ }
            calendar.classList.toggle("hide-todos", hidden);
            toggle.setAttribute("aria-pressed", String(!hidden));
        };
        document.addEventListener("click", event => {
            if (!event.target.closest("[data-toggle-todos]")) return;
            const calendar = document.getElementById("calendar");
            const hidden = !calendar.classList.contains("hide-todos");
            try { localStorage.setItem(KEY, hidden ? "hidden" : "shown"); } catch (e) { /* ohne Speicher */ }
            apply();
        });
        document.addEventListener("htmx:afterSwap", apply);
        apply();
    }

    /* --- Service Worker und Push ------------------------------------------ */

    function initPush() {
        if (!("serviceWorker" in navigator)) return;
        // Service Worker auf allen Seiten registrieren (macht Nestwerk installierbar)
        navigator.serviceWorker.register("/sw.js").catch(() => { /* z. B. ohne HTTPS */ });

        const button = document.querySelector("[data-push-subscribe]");
        const state = document.querySelector("[data-push-state]");
        if (!button || !state) return;
        if (!("PushManager" in window) || !("Notification" in window)) return;

        const toKey = base64 => {
            const padded = (base64 + "===".slice((base64.length + 3) % 4)).replace(/-/g, "+").replace(/_/g, "/");
            return Uint8Array.from(atob(padded), c => c.charCodeAt(0));
        };
        const show = async () => {
            const registration = await navigator.serviceWorker.ready;
            const subscription = await registration.pushManager.getSubscription();
            if (Notification.permission === "denied") {
                state.textContent = "Benachrichtigungen sind in diesem Browser blockiert. Du kannst sie in den Website-Einstellungen erlauben.";
                button.hidden = true;
            } else if (subscription) {
                state.textContent = "Push ist auf diesem Gerät aktiv.";
                button.hidden = true;
            } else {
                state.textContent = "Auf diesem Gerät ist Push noch nicht aktiviert.";
                button.hidden = false;
            }
        };
        button.addEventListener("click", async () => {
            button.disabled = true;
            try {
                const permission = await Notification.requestPermission();
                if (permission !== "granted") {
                    showToast("Ohne Erlaubnis kann Nestwerk keine Push-Nachrichten schicken.", "error");
                    return;
                }
                const registration = await navigator.serviceWorker.ready;
                const subscription = await registration.pushManager.subscribe({
                    userVisibleOnly: true,
                    applicationServerKey: toKey(button.dataset.vapidKey)
                });
                const token = document.querySelector('meta[name="_csrf"]')?.content;
                const header = document.querySelector('meta[name="_csrf_header"]')?.content;
                const headers = { "Content-Type": "application/json" };
                if (token && header) headers[header] = token;
                const response = await fetch("/settings/notifications/push-subscriptions", {
                    method: "POST", headers, body: JSON.stringify(subscription.toJSON())
                });
                if (!response.ok) throw new Error(await response.text());
                window.location.reload();
            } catch (error) {
                showToast(error.message || "Push konnte nicht aktiviert werden.", "error");
            } finally {
                button.disabled = false;
            }
        });
        show().catch(() => { state.textContent = "Push ist hier nicht verfügbar (Nestwerk braucht HTTPS)."; });
    }

    /* --- Filterformulare: bei Änderung sofort absenden --------------------- */

    function initAutoSubmit() {
        document.addEventListener("change", event => {
            const form = event.target.closest("form[data-auto-submit]");
            if (!form || event.target.matches("input[type=search], input[type=text]")) return;
            form.requestSubmit();
        });
    }

    /* --- Begrüßung nach Tageszeit ---------------------------------------- */

    function initGreeting() {
        const el = document.querySelector("[data-greeting]");
        if (!el) return;
        const hour = new Date().getHours();
        const text = hour < 5 ? "Gute Nacht"
            : hour < 11 ? "Guten Morgen"
            : hour < 17 ? "Guten Tag"
            : hour < 22 ? "Guten Abend"
            : "Gute Nacht";
        el.textContent = text;
    }

    document.addEventListener("DOMContentLoaded", () => {
        initTheme();
        initNav();
        initDialogs();
        initConfirm();
        initPasswordToggles();
        initFilters();
        initCopyButtons();
        initRawUploads();
        initHtmx();
        initGreeting();
        initEventForm();
        initChoreForm();
        initBookForms();
        initAutoSubmit();
        initBoard();
        initCollaboration();
        initPush();
        initCalendarTodos();
        consumeToasts(document);
    });
})();
