/* Nestwerk – kleine, abhängigkeitsfreie UI-Helfer. htmx wird separat geladen. */
(() => {
    "use strict";

    const root = document.documentElement;

    /* --- Farbschema: System → Hell → Dunkel ------------------------------ */

    const THEME_KEY = "nestwerk-theme";
    const THEME_LABELS = { system: "System", light: "Hell", dark: "Dunkel" };
    const reducedMotion = () => matchMedia("(prefers-reduced-motion: reduce)").matches;
    const haptic = (pattern = 8) => { try { navigator.vibrate?.(pattern); } catch (e) { /* ohne Vibration */ } };

    function readTheme() {
        try {
            const value = localStorage.getItem(THEME_KEY);
            return value === "light" || value === "system" ? value : "dark";
        } catch (e) {
            return "dark";
        }
    }

    function applyTheme(mode) {
        root.dataset.themeMode = mode;
        root.dataset.theme = mode;
        const light = mode === "light" || (mode === "system" && matchMedia("(prefers-color-scheme: light)").matches);
        document.querySelector('meta[name="theme-color"]')?.setAttribute("content", light ? "#f3f3f9" : "#08080d");
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
            const order = ["dark", "light", "system"];
            const next = order[(order.indexOf(root.dataset.themeMode) + 1) % order.length];
            try {
                if (next === "dark") localStorage.removeItem(THEME_KEY);
                else localStorage.setItem(THEME_KEY, next);
            } catch (e) { /* nur für diese Sitzung */ }
            const swap = () => applyTheme(next);
            if (document.startViewTransition && !reducedMotion()) document.startViewTransition(swap);
            else swap();
        });
    }

    /* --- Navigation und Benutzermenü ------------------------------------- */

    /** Menus work by delegation, so menus that arrive with a swapped page part (the calendar toolbar) work too. */
    function initNav() {
        const setOpen = (menu, open) => {
            const button = menu.querySelector("[data-menu-toggle]");
            const panel = menu.querySelector("[data-menu-panel]");
            panel.hidden = !open;
            button.setAttribute("aria-expanded", String(open));
            if (open) panel.querySelector("button, a")?.focus();
        };
        const openMenus = () => [...document.querySelectorAll("[data-menu]")]
            .filter(menu => !menu.querySelector("[data-menu-panel]").hidden);
        document.addEventListener("click", event => {
            const toggle = event.target.closest("[data-menu-toggle]");
            const own = toggle?.closest("[data-menu]");
            openMenus().filter(menu => menu !== own && !menu.contains(event.target)).forEach(menu => setOpen(menu, false));
            if (own) setOpen(own, own.querySelector("[data-menu-panel]").hidden);
        });
        document.addEventListener("keydown", event => {
            if (event.key !== "Escape") return;
            openMenus().forEach(menu => {
                setOpen(menu, false);
                menu.querySelector("[data-menu-toggle]").focus();
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
                closeDialog(closer.closest("dialog"));
                return;
            }
            // Klick auf den Hintergrund schließt den Dialog
            if (event.target instanceof HTMLDialogElement && event.target.open) {
                const box = event.target.getBoundingClientRect();
                const inside = event.clientX >= box.left && event.clientX <= box.right
                    && event.clientY >= box.top && event.clientY <= box.bottom;
                if (!inside) closeDialog(event.target);
            }
        });
        // Escape: ebenfalls mit Animation schließen
        document.addEventListener("cancel", event => {
            if (!(event.target instanceof HTMLDialogElement)) return;
            event.preventDefault();
            closeDialog(event.target);
        }, true);
    }

    function closeDialog(dialog) {
        if (!dialog?.open || dialog.classList.contains("is-closing")) return;
        if (reducedMotion()) {
            dialog.close();
            return;
        }
        const done = () => {
            if (!dialog.classList.contains("is-closing")) return;
            dialog.classList.remove("is-closing");
            dialog.close();
        };
        dialog.classList.add("is-closing");
        dialog.addEventListener("animationend", done, { once: true });
        setTimeout(done, 400);
    }

    /* --- Bestätigung vor destruktiven Aktionen ----------------------------- */

    function initConfirm() {
        const dialog = document.getElementById("confirm-dialog");
        if (!dialog) return;
        const ok = dialog.querySelector("[data-confirm-ok]");
        let onConfirm = null;
        // red and "Löschen" only for real deletions; everything else is confirmed with its own neutral label
        const ask = (form, proceed) => {
            onConfirm = proceed;
            const danger = form.dataset.confirmTone === "danger";
            dialog.querySelector("[data-confirm-text]").textContent = form.dataset.confirm;
            dialog.querySelector("[data-confirm-danger-note]").hidden = !danger;
            ok.classList.toggle("button-danger", danger);
            ok.textContent = form.dataset.confirmLabel || (danger ? "Löschen" : "Bestätigen");
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
            const passphrase = form.querySelector("[data-upload-passphrase]")?.value;
            if (passphrase) xhr.setRequestHeader("X-Backup-Passphrase", encodeURIComponent(passphrase));
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
        if (kind === "error") haptic([12, 60, 12]);
        setTimeout(() => {
            if (!toast.isConnected || toast.classList.contains("is-dragging")) return;
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

        setUpRepeat(form);
    }

    /** Preset choice of the repeat rule with a free RRULE field for "custom". */
    function setUpRepeat(form) {
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

    // Quick booking with a chore select: take the minutes of the chosen chore into the effort choices
    function applyChoreMinutes(select) {
        const form = select.closest("[data-book-form]");
        const option = select.selectedOptions[0];
        if (!form || !option) return;
        form.querySelectorAll("input[name=effort]").forEach(radio => {
            const minutes = option.dataset[radio.value.toLowerCase()];
            radio.dataset.minutes = minutes;
            const label = radio.parentElement.querySelector(".effort-minutes");
            if (label) label.textContent = minutes + " Min";
        });
        const minutes = form.querySelector("[data-book-minutes]");
        const checked = form.querySelector("input[name=effort]:checked");
        if (minutes && checked && !minutes.dataset.touched) minutes.value = checked.dataset.minutes;
    }

    function initBookForms() {
        document.querySelectorAll("[data-book-chore]").forEach(applyChoreMinutes);
        document.addEventListener("change", event => {
            if (event.target.matches("[data-book-chore]")) applyChoreMinutes(event.target);
        });
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
        if (form) setUpChoreForm(form);
    }

    /** Sections per kind and the order of members for "reihum"; also used for the chore form in calendar dialogs. */
    function setUpChoreForm(form) {
        setUpRepeat(form);
        const sections = form.querySelectorAll("[data-kind-section]");
        const syncKind = () => {
            const kind = form.querySelector("[data-chore-kind]:checked")?.value;
            sections.forEach(s => { s.hidden = !s.dataset.kindSection.split(" ").includes(kind); });
        };
        form.querySelectorAll("[data-chore-kind]").forEach(r => r.addEventListener("change", syncKind));
        syncKind();

        // order for "reihum": in the order the members are tapped
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
            form.querySelectorAll("[data-member-order-value]").forEach(hidden => hidden.remove());
            order.forEach(id => {
                const hidden = document.createElement("input");
                hidden.type = "hidden";
                hidden.name = "memberIds";
                hidden.value = id;
                hidden.dataset.memberOrderValue = "";
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
                onChoose: () => haptic(),
                onMove: evt => { markDropTarget(evt.to.closest(".board-column")); },
                onEnd: async evt => {
                    markDropTarget(null);
                    markDragged();
                    if (evt.from === evt.to) return;
                    haptic(12);
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
                        card.animate([{ transform: "scale(1.06)" }, { transform: "scale(1)" }],
                            { duration: 380, easing: "cubic-bezier(0.34, 1.56, 0.64, 1)" });
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

    /* --- Bewegung: gestaffeltes Einblenden, Zähler, Lichtpunkt, Konfetti ------ */

    const STAGGER_LISTS = ".todo-list, .chore-list, .areas, .rings, .contact-tiles, .members, .board-cards, "
        + ".notification-list, .agenda, .reward-grid, .balances, .stack, .month";

    function stagger(scope) {
        scope.querySelectorAll("main").forEach(main => {
            [...main.children].forEach((el, i) => el.style.setProperty("--i", Math.min(i, 10)));
        });
        scope.querySelectorAll(STAGGER_LISTS).forEach(list => {
            [...list.children].forEach((el, i) => el.style.setProperty("--i", Math.min(i, 14)));
        });
    }

    function countUp(scope) {
        scope.querySelectorAll("[data-count-up]").forEach(el => {
            const target = Number(el.textContent);
            if (!Number.isFinite(target) || reducedMotion()) return;
            const start = performance.now();
            const duration = 1200;
            const step = now => {
                const t = Math.min(1, (now - start) / duration);
                el.textContent = String(Math.round(target * (1 - Math.pow(1 - t, 3))));
                if (t < 1) requestAnimationFrame(step);
            };
            requestAnimationFrame(step);
        });
    }

    function burst(anchor) {
        if (!anchor || reducedMotion()) return;
        const box = anchor.getBoundingClientRect();
        const spot = document.createElement("div");
        spot.className = "burst";
        spot.style.left = box.left + box.width / 2 + "px";
        spot.style.top = box.top + box.height / 2 + "px";
        const colors = ["#2ee6a8", "#22d3ee", "#8b6cff", "#ff9f1c", "#ff4fd8"];
        for (let i = 0; i < 14; i++) {
            const dot = document.createElement("i");
            const angle = (Math.PI * 2 * i) / 14 + Math.random() * 0.4;
            const distance = 26 + Math.random() * 30;
            dot.style.setProperty("--x", Math.cos(angle) * distance + "px");
            dot.style.setProperty("--y", Math.sin(angle) * distance + "px");
            dot.style.setProperty("--c", colors[i % colors.length]);
            spot.append(dot);
        }
        document.body.append(spot);
        setTimeout(() => spot.remove(), 800);
    }

    function initMotion() {
        stagger(document);
        countUp(document);
        setTimeout(() => root.classList.remove("page-enter"), 1600);

        // Lichtpunkt folgt dem Zeiger auf den Bereichskacheln
        document.addEventListener("pointermove", event => {
            const tile = event.target.closest?.(".area");
            if (!tile) return;
            const box = tile.getBoundingClientRect();
            tile.style.setProperty("--mx", ((event.clientX - box.left) / box.width) * 100 + "%");
            tile.style.setProperty("--my", ((event.clientY - box.top) / box.height) * 100 + "%");
        }, { passive: true });

        // Frisch abgehakt: kleines Konfetti am Haken
        document.addEventListener("htmx:load", event => {
            const el = event.detail.elt;
            if (!(el instanceof Element)) return;
            if (el.matches(".todo-row.is-checked")) {
                burst(el.querySelector(".check"));
                haptic(14);
            } else if (el.matches(".chore-chip.is-done")) {
                burst(el.querySelector(".chore-check"));
                haptic(14);
            }
        });
    }

    /* --- Wellen beim Antippen von Buttons ------------------------------------ */

    function initRipples() {
        document.addEventListener("pointerdown", event => {
            const button = event.target.closest(".button");
            if (!button || reducedMotion()) return;
            const box = button.getBoundingClientRect();
            const ripple = document.createElement("span");
            ripple.className = "ripple";
            ripple.style.left = event.clientX - box.left + "px";
            ripple.style.top = event.clientY - box.top + "px";
            button.append(ripple);
            ripple.addEventListener("animationend", () => ripple.remove(), { once: true });
        });
    }

    /* --- Ziehen: Klick direkt nach dem Ablegen ignorieren --------------------- */

    let draggedAt = 0;
    const markDragged = () => { draggedAt = Date.now(); };

    /* --- Updates: während des Neustarts auf die App warten -------------------- */
    // Antwortet die Statusabfrage nicht mehr, startet Nestwerk gerade neu: Hinweis zeigen und neu laden,
    // sobald /actuator/health wieder antwortet.
    function initUpdateRestart() {
        let waiting = false;
        const onFailure = event => {
            const section = event.detail?.elt?.closest?.("#update-status");
            if (!section || waiting) return;
            waiting = true;
            section.removeAttribute("hx-trigger");
            section.querySelector("[data-update-restart]")?.removeAttribute("hidden");
            const poll = async () => {
                try {
                    const response = await fetch("/actuator/health", { cache: "no-store" });
                    if (response.ok) {
                        location.reload();
                        return;
                    }
                } catch (e) { /* noch nicht wieder da */ }
                setTimeout(poll, 3000);
            };
            setTimeout(poll, 3000);
        };
        document.addEventListener("htmx:sendError", onFailure);
        document.addEventListener("htmx:responseError", onFailure);
    }

    /* --- Blocker-Warnung im Terminformular ------------------------------------ */
    // Ein Vorschlag übernimmt die freie Zeit; jede neue Zeit wird erneut geprüft statt „trotzdem“ gespeichert.
    function initBlockerWarning() {
        const box = document.querySelector("[data-blocker-warning]");
        if (!box) return;
        const form = box.closest("form");
        const unconfirm = label => {
            box.querySelector("[data-blocker-confirm]")?.remove();
            const submit = form.querySelector("[data-blocker-submit]");
            if (submit) submit.textContent = label;
        };
        box.addEventListener("click", event => {
            const chip = event.target.closest("[data-alt-start-date]");
            if (!chip) return;
            const set = (name, value) => {
                const field = form.querySelector(`[name="${name}"]`);
                if (field) field.value = value;
            };
            set("startDate", chip.dataset.altStartDate);
            set("startTime", chip.dataset.altStartTime.slice(0, 5));
            set("endDate", chip.dataset.altEndDate);
            set("endTime", chip.dataset.altEndTime.slice(0, 5));
            box.querySelectorAll(".chip").forEach(c => c.classList.toggle("is-picked", c === chip));
            unconfirm("Mit dieser Zeit speichern");
            haptic();
        });
        form.querySelectorAll('[name="startDate"], [name="startTime"], [name="endDate"], [name="endTime"]')
            .forEach(field => field.addEventListener("input", () => unconfirm("Speichern")));
    }

    /* --- Ruhe-Ziel: Fenster nur bei „freie Zeitfenster“ ---------------------- */
    // Neue Zeit: Wechsel der Art lädt die passenden Vorgaben (Uhrzeiten, Tage, Regeln)
    /**
     * Barcode scannen: Kamera mit BarcodeDetector, wo der Browser ihn kennt, sonst Nummer eintippen. Mit
     * data-barcode-target füllt der Code ein Feld, sonst wird er an die Adresse in data-barcode-scan gesendet.
     */
    function initBarcodeScan() {
        document.querySelectorAll("[data-barcode-scan]").forEach(button => {
            button.addEventListener("click", () => openScanner(button));
        });
    }

    function openScanner(button) {
        const dialog = document.createElement("dialog");
        dialog.className = "dialog barcode-dialog";
        dialog.innerHTML = '<div class="sheet-grip" aria-hidden="true"></div>'
            + '<h2>Barcode scannen</h2>'
            + '<video class="barcode-video" playsinline muted hidden></video>'
            + '<p class="muted barcode-hint">Kamera wird gestartet …</p>'
            + '<form class="barcode-manual"><label for="barcode-code">Oder Nummer eingeben</label>'
            + '<div class="barcode-row"><input id="barcode-code" inputmode="numeric" pattern="[0-9 ]{6,20}" required>'
            + '<button type="submit" class="button">Übernehmen</button></div></form>'
            + '<div class="form-actions"><button type="button" class="button button-ghost" data-close>Abbrechen</button></div>';
        document.body.append(dialog);
        const video = dialog.querySelector("video");
        const hint = dialog.querySelector(".barcode-hint");
        let stream = null;
        let done = false;
        const close = () => {
            done = true;
            if (stream) stream.getTracks().forEach(t => t.stop());
            dialog.close();
            dialog.remove();
        };
        const finish = code => {
            close();
            const clean = String(code).replace(/\D/g, "");
            const target = button.dataset.barcodeTarget && document.querySelector(button.dataset.barcodeTarget);
            if (target) {
                target.value = clean;
                target.dispatchEvent(new Event("change", {bubbles: true}));
                return;
            }
            const form = document.createElement("form");
            form.method = "post";
            form.action = button.dataset.barcodeScan;
            const add = (name, value) => {
                const input = document.createElement("input");
                input.type = "hidden";
                input.name = name;
                input.value = value;
                form.append(input);
            };
            add("code", clean);
            const token = document.querySelector('meta[name="_csrf"]')?.content;
            if (token) add("_csrf", token);
            document.body.append(form);
            form.submit();
        };
        dialog.querySelector("[data-close]").addEventListener("click", close);
        dialog.addEventListener("cancel", close);
        dialog.querySelector(".barcode-manual").addEventListener("submit", event => {
            event.preventDefault();
            finish(dialog.querySelector("#barcode-code").value);
        });
        dialog.showModal();

        if (!("BarcodeDetector" in window) || !navigator.mediaDevices?.getUserMedia) {
            hint.textContent = "Dieser Browser kann keine Barcodes erkennen – bitte die Nummer unter dem Strichcode eintippen.";
            dialog.querySelector("#barcode-code").focus();
            return;
        }
        const detector = new window.BarcodeDetector({formats: ["ean_13", "ean_8", "upc_a", "upc_e"]});
        navigator.mediaDevices.getUserMedia({video: {facingMode: "environment"}}).then(media => {
            stream = media;
            if (done) {
                media.getTracks().forEach(t => t.stop());
                return;
            }
            video.srcObject = media;
            video.hidden = false;
            hint.textContent = "Strichcode vor die Kamera halten.";
            return video.play().then(() => {
                const tick = () => {
                    if (done) return;
                    detector.detect(video).then(codes => {
                        if (codes.length) finish(codes[0].rawValue);
                        else setTimeout(tick, 250);
                    }).catch(() => setTimeout(tick, 500));
                };
                tick();
            });
        }).catch(() => {
            hint.textContent = "Keine Kamera verfügbar – bitte die Nummer eintippen.";
        });
    }

    /** Rezeptformular: weitere Zutatenzeile anhängen (Kopie der letzten, Index hochgezählt). */
    function initIngredientRows() {
        document.querySelectorAll("[data-ingredient-rows]").forEach(box => {
            const add = box.querySelector("[data-add-ingredient]");
            add.addEventListener("click", () => {
                const rows = box.querySelectorAll(".ingredient-row");
                const last = rows[rows.length - 1];
                const copy = last.cloneNode(true);
                copy.querySelectorAll("input, select").forEach(field => {
                    field.name = field.name.replace(/rows\[(\d+)\]/, (m, n) => "rows[" + (Number(n) + 1) + "]");
                    field.value = "";
                });
                last.after(copy);
                copy.querySelector(".ingredient-amount").focus();
            });
        });
    }

    function initBlockerKindDefaults() {
        const form = document.querySelector('[data-blocker-new="true"]');
        if (!form) return;
        form.addEventListener("change", event => {
            if (event.target.name !== "kind") return;
            const url = new URL(location.href);
            url.searchParams.set("kind", event.target.value);
            location.replace(url);
        });
    }

    /* School: show the fields of an exam or an appointment and preset the participants of the kind */
    function initSchoolExamForms() {
        document.querySelectorAll("[data-exam-form]").forEach(form => {
            const kind = form.querySelector("select[name=kind]");
            const defaults = JSON.parse(form.dataset.defaults || "{}");
            const apply = presetMembers => {
                const exam = kind.selectedOptions[0]?.dataset.exam === "true";
                form.querySelectorAll("[data-exam-only]").forEach(el => { el.hidden = !exam; });
                form.querySelectorAll("[data-event-only]").forEach(el => { el.hidden = exam; });
                if (presetMembers) {
                    const chosen = (defaults[kind.value] || []).map(String);
                    form.querySelectorAll("input[name=memberId]").forEach(box => {
                        box.checked = chosen.includes(box.value);
                    });
                }
            };
            kind.addEventListener("change", () => apply(true));
            apply(form.dataset.presetMembers === "true");
        });
    }

    function initGoalForms() {
        document.querySelectorAll("[data-goal-form]").forEach(form => {
            const kind = form.querySelector("[data-goal-kind]");
            const sync = () => {
                const windows = kind.value === "WINDOWS";
                form.querySelector("[data-goal-window]").hidden = !windows;
                form.querySelector("[data-goal-unit]").textContent = windows ? "Anzahl" : "Stunden";
                const label = form.querySelector('[name="label"]');
                if (label && (label.value === "Freie Abende" || label.value === "Ruhezeit")) {
                    label.value = windows ? "Freie Abende" : "Ruhezeit";
                }
            };
            kind.addEventListener("change", sync);
            sync();
        });
    }

    function initDragClickGuard() {
        document.addEventListener("click", event => {
            if (Date.now() - draggedAt < 350) {
                event.preventDefault();
                event.stopPropagation();
            }
        }, true);
    }

    function markDropTarget(el) {
        document.querySelectorAll(".is-drop-target").forEach(t => { if (t !== el) t.classList.remove("is-drop-target"); });
        el?.classList.add("is-drop-target");
    }

    let sortableActive = false;

    function sortable(el, options) {
        if (!window.Sortable || el.dataset.sortableReady) return null;
        el.dataset.sortableReady = "true";
        return window.Sortable.create(el, {
            animation: 200,
            easing: "cubic-bezier(0.34, 1.56, 0.64, 1)",
            delay: 220,
            delayOnTouchOnly: true,
            touchStartThreshold: 6,
            ...options,
            onChoose: evt => {
                sortableActive = true;
                haptic();
                options.onChoose?.(evt);
            },
            onStart: evt => {
                document.body.classList.add("is-dragging-somewhere");
                options.onStart?.(evt);
            },
            onUnchoose: () => { sortableActive = false; },
            onEnd: evt => {
                sortableActive = false;
                document.body.classList.remove("is-dragging-somewhere");
                markDropTarget(null);
                markDragged();
                options.onEnd?.(evt);
            }
        });
    }

    async function postForm(url, params) {
        const token = document.querySelector('meta[name="_csrf"]')?.content;
        const header = document.querySelector('meta[name="_csrf_header"]')?.content;
        const headers = { "HX-Request": "true", "Content-Type": "application/x-www-form-urlencoded" };
        if (token && header) headers[header] = token;
        const response = await fetch(url, { method: "POST", headers, body: new URLSearchParams(params) });
        const type = response.headers.get("Content-Type") || "";
        const text = type.startsWith("text/plain") ? await response.text() : "";
        if (!response.ok) throw new Error(text || "Das hat leider nicht geklappt.");
        return text;
    }

    /* --- Startseite: Bereiche und Kacheln per Ziehen ordnen ------------------ */

    function restoreOrder(container, attr, key) {
        let order = [];
        try { order = JSON.parse(localStorage.getItem(key) || "[]"); } catch (e) { /* ohne Speicher */ }
        if (!Array.isArray(order)) return;
        order.forEach(id => {
            const el = container.querySelector(`:scope > [${attr}="${CSS.escape(id)}"]`);
            if (el) container.append(el);
        });
    }

    function saveOrder(container, attr, key) {
        const order = [...container.children].map(el => el.getAttribute(attr)).filter(Boolean);
        try { localStorage.setItem(key, JSON.stringify(order)); } catch (e) { /* nur für diese Sitzung */ }
    }

    function initHomeSorting() {
        const dash = document.querySelector("[data-dash]");
        if (dash) {
            restoreOrder(dash, "data-panel", "nestwerk-home-panels");
            sortable(dash, {
                handle: "[data-panel-grip], .home-todos-head h2",
                draggable: "[data-panel]",
                onEnd: () => saveOrder(dash, "data-panel", "nestwerk-home-panels")
            });
        }
        const areas = document.querySelector("[data-sortable-areas]");
        if (areas) {
            restoreOrder(areas, "data-area", "nestwerk-home-areas");
            sortable(areas, {
                draggable: "[data-area]",
                delay: 300,
                onEnd: () => saveOrder(areas, "data-area", "nestwerk-home-areas")
            });
        }
    }

    /* --- Kalender: Termine auf einen anderen Tag ziehen ---------------------- */

    const dayNumber = iso => Date.UTC(+iso.slice(0, 4), +iso.slice(5, 7) - 1, +iso.slice(8, 10)) / 86400000;
    const isoOf = day => new Date(day * 86400000).toISOString().slice(0, 10);

    function reloadCalendar() {
        if (window.htmx && document.getElementById("calendar")) {
            window.htmx.ajax("GET", window.location.href, { target: "#calendar", select: "#calendar", swap: "outerHTML" });
        } else {
            window.location.reload();
        }
    }

    function initCalendarDrag() {
        const setup = () => document.querySelectorAll("#calendar [data-drop-date]").forEach(cell => {
            sortable(cell, {
                group: "calendar",
                sort: false,
                draggable: ".occ[data-movable]",
                forceFallback: true,
                fallbackOnBody: true,
                onMove: evt => { markDropTarget(evt.to.closest(".day, .week-day")); },
                onEnd: async evt => {
                    if (evt.from === evt.to) return;
                    const item = evt.item;
                    const offset = dayNumber(evt.to.dataset.dropDate) - dayNumber(evt.from.dataset.dropDate);
                    const date = isoOf(dayNumber(item.dataset.start) + offset);
                    haptic(12);
                    try {
                        showToast(await postForm(`/calendar/events/${item.dataset.eventId}/move`, { date }), "success");
                    } catch (error) {
                        showToast(error.message, "error");
                    }
                    reloadCalendar();
                }
            });
        });
        setup();
        document.addEventListener("htmx:afterSettle", setup);
    }

    /* --- Essensplan: Rezepte in Mahlzeiten ziehen, Einträge verschieben ------ */

    /* Rezeptsuche im Essensplan: erst beim Anklicken eines Planungsfelds */
    function initPlanPicker() {
        const dialog = document.getElementById("plan-pick");
        if (!dialog) return;
        const form = dialog.querySelector("form");
        const input = dialog.querySelector("[data-plan-filter]");
        const list = dialog.querySelector(".plan-suggestions");
        const items = [...list.children];
        const empty = dialog.querySelector("[data-plan-empty]");
        const filter = () => {
            const q = input.value.trim().toLowerCase();
            const rank = li => {
                const t = li.dataset.title;
                if (q === "" || t.startsWith(q)) return 0;
                if (t.split(/[\s,;()-]+/).some(w => w.startsWith(q))) return 1;
                return t.includes(q) ? 2 : -1;
            };
            const ranked = items.map(li => [li, rank(li)]);
            ranked.filter(([, r]) => r >= 0).sort((a, b) => a[1] - b[1]).forEach(([li]) => list.append(li));
            ranked.forEach(([li, r]) => { li.hidden = r < 0; });
            empty.hidden = q === "" || ranked.some(([, r]) => r >= 0);
        };
        input.addEventListener("input", filter);
        input.addEventListener("keydown", event => {
            if (event.key !== "Enter" || input.value.trim() === "") return;
            const first = items.find(li => !li.hidden);
            if (first) {
                event.preventDefault();
                first.querySelector("button").click();
            }
        });
        document.addEventListener("click", event => {
            const opener = event.target.closest("[data-plan-pick]");
            if (!opener) return;
            form.elements.day.value = opener.dataset.day;
            form.elements.slotId.value = opener.dataset.slot;
            dialog.querySelector("[data-plan-pick-title]").textContent = opener.dataset.label;
            input.value = "";
            filter();
            dialog.showModal();
            input.focus();
        });
    }

    function initMealPlanDrag() {
        initPlanPicker();
        const board = document.querySelector('[data-plan-board="true"]');
        if (!board) return;
        board.querySelectorAll(".plan-slot[data-slot]").forEach(slot => {
            sortable(slot, {
                group: { name: "meals", pull: true, put: true },
                sort: false,
                draggable: ".meal-chip",
                filter: "h3, button",
                preventOnFilter: false,
                forceFallback: true,
                fallbackOnBody: true,
                onMove: evt => { markDropTarget(evt.to); },
                onAdd: async evt => {
                    markDropTarget(null);
                    const item = evt.item;
                    const target = { day: slot.dataset.day, slotId: slot.dataset.slot };
                    haptic(12);
                    try {
                        await postForm(`/kitchen/plan/meals/${item.dataset.mealId}/move`, target);
                        location.reload();
                    } catch (error) {
                        evt.from.append(item);
                        showToast(error.message, "error");
                    }
                }
            });
        });
    }

    /* --- Wischen an Zeilen: rechts erledigen, links öffnen ------------------ */

    function initSwipeRows() {
        let s = null;
        const reset = () => {
            if (!s) return;
            const row = s.row;
            row.classList.add("swipe-release");
            row.style.setProperty("--swipe", "0px");
            setTimeout(() => {
                row.classList.remove("is-swiping", "swipe-left", "swipe-right", "swipe-armed", "swipe-release");
                row.style.removeProperty("--swipe");
                row.style.removeProperty("--swipe-progress");
            }, 360);
            s = null;
        };
        document.addEventListener("pointerdown", event => {
            if (event.pointerType === "mouse" || sortableActive) return;
            const row = event.target.closest(".todo-row, .chore-chip");
            if (!row || event.target.closest("button, input, select, textarea, dialog, summary")) return;
            s = {
                row, x: event.clientX, y: event.clientY, dx: 0, active: false, id: event.pointerId,
                // nur in Richtungen mitgehen, für die es auch eine Aktion gibt
                right: !!row.querySelector("form.todo-check, .chore-check:not(.is-static):not(.is-checked)"),
                left: !!row.querySelector(".todo-title, .chore-body")
            };
        }, { passive: true });
        document.addEventListener("pointermove", event => {
            if (!s || event.pointerId !== s.id) return;
            if (sortableActive) { reset(); return; }
            const dx = event.clientX - s.x;
            const dy = event.clientY - s.y;
            if (!s.active) {
                if (Math.abs(dy) > 10 && Math.abs(dy) > Math.abs(dx)) { s = null; return; }
                if (Math.abs(dx) < 14) return;
                s.active = true;
                s.row.classList.add("is-swiping");
            }
            s.dx = dx;
            const allowed = dx > 0 ? s.right : s.left;
            const limit = allowed ? s.row.offsetWidth * 0.45 : 24;
            const shown = Math.sign(dx) * Math.min(Math.abs(dx), limit + (Math.abs(dx) - limit) * 0.2);
            const threshold = Math.min(110, s.row.offsetWidth * 0.3);
            const armed = allowed && Math.abs(dx) >= threshold;
            if (armed !== s.row.classList.contains("swipe-armed")) haptic(armed ? 10 : 4);
            s.row.style.setProperty("--swipe", shown + "px");
            s.row.style.setProperty("--swipe-progress", Math.min(1, Math.abs(dx) / threshold).toFixed(2));
            s.row.classList.toggle("swipe-right", dx > 0);
            s.row.classList.toggle("swipe-left", dx < 0);
            s.row.classList.toggle("swipe-armed", armed);
        }, { passive: true });
        const finish = event => {
            if (!s || event.pointerId !== s.id) return;
            if (!s.active) { s = null; return; }
            const { row, dx } = s;
            const armed = row.classList.contains("swipe-armed");
            markDragged();
            reset();
            if (!armed) return;
            if (dx > 0) {
                const form = row.querySelector("form.todo-check");
                if (form) form.requestSubmit();
                else row.querySelector(".chore-check:not(.is-static):not(.is-checked)")?.click();
            } else {
                const link = row.querySelector(".todo-title, .chore-body");
                if (link?.href) window.location.href = link.href;
            }
        };
        document.addEventListener("pointerup", finish);
        document.addEventListener("pointercancel", event => {
            if (s && event.pointerId === s.id) reset();
        });
    }

    /* --- Wischen zum Blättern (Kalender, Wochenplan) ------------------------- */

    let slideDirection = null;

    function initSwipeNav() {
        let s = null;
        document.addEventListener("touchstart", event => {
            const area = event.target.closest("[data-swipe-area]");
            if (!area || event.touches.length !== 1 || sortableActive
                || event.target.closest(".todo-row, .chore-chip, .board, .segmented, input, textarea, select, dialog, .can-scroll-x")) {
                s = null;
                return;
            }
            const t = event.touches[0];
            s = { area, x: t.clientX, y: t.clientY, at: Date.now() };
        }, { passive: true });
        document.addEventListener("touchend", event => {
            if (!s || sortableActive || Date.now() - draggedAt < 350) { s = null; return; }
            const t = event.changedTouches[0];
            const dx = t.clientX - s.x;
            const dy = t.clientY - s.y;
            const quick = Date.now() - s.at < 600;
            const area = s.area;
            s = null;
            if (!quick || Math.abs(dx) < 70 || Math.abs(dy) > Math.abs(dx) * 0.6) return;
            const link = area.querySelector(dx < 0 ? "[data-swipe-next]" : "[data-swipe-prev]");
            if (!link) return;
            slideDirection = dx < 0 ? "slide-next" : "slide-prev";
            haptic(6);
            link.click();
        }, { passive: true });
        document.addEventListener("click", event => {
            const link = event.target.closest("[data-swipe-next], [data-swipe-prev]");
            if (link && !slideDirection) slideDirection = link.matches("[data-swipe-next]") ? "slide-next" : "slide-prev";
        });
        document.addEventListener("htmx:afterSwap", () => {
            const calendar = document.getElementById("calendar");
            if (calendar && slideDirection && !reducedMotion()) calendar.classList.add(slideDirection);
            slideDirection = null;
        });
    }

    /* --- Toasts zur Seite wegwischen ----------------------------------------- */

    function initToastSwipe() {
        let s = null;
        document.addEventListener("pointerdown", event => {
            const toast = event.target.closest(".toast");
            // buttons in a toast (e.g. "Rückgängig") must receive their click
            if (!toast || event.target.closest("button")) return;
            s = { toast, x: event.clientX, id: event.pointerId };
            toast.classList.add("is-dragging");
            toast.setPointerCapture?.(event.pointerId);
        });
        document.addEventListener("pointermove", event => {
            if (!s || event.pointerId !== s.id) return;
            const dx = event.clientX - s.x;
            s.toast.style.transform = `translateX(${dx}px) rotate(${dx / 30}deg)`;
            s.toast.style.opacity = String(Math.max(0.2, 1 - Math.abs(dx) / 260));
        });
        const end = event => {
            if (!s || event.pointerId !== s.id) return;
            const { toast } = s;
            const dx = event.clientX - s.x;
            s = null;
            toast.classList.remove("is-dragging");
            if (Math.abs(dx) > 80) {
                toast.classList.add("is-flung");
                toast.style.transform = `translateX(${Math.sign(dx) * 120}%)`;
                setTimeout(() => toast.remove(), 260);
            } else {
                toast.animate([{ transform: toast.style.transform }, { transform: "none" }],
                    { duration: 300, easing: "cubic-bezier(0.34, 1.56, 0.64, 1)" });
                toast.style.transform = "";
                toast.style.opacity = "";
            }
        };
        document.addEventListener("pointerup", end);
        document.addEventListener("pointercancel", end);
    }

    /* --- Blätter von unten: am Griff nach unten wegziehen -------------------- */

    function initSheetDrag() {
        let s = null;
        const compact = () => matchMedia("(max-width: 40rem)").matches;
        document.addEventListener("pointerdown", event => {
            const dialog = event.target.closest("dialog.dialog");
            if (!dialog?.open || !compact() || event.target.closest("input, textarea, select, button, a")) return;
            const box = dialog.getBoundingClientRect();
            const onGrip = event.clientY - box.top < 56 || dialog.classList.contains("nav-sheet");
            if (!onGrip || dialog.scrollTop > 0) return;
            s = { dialog, y: event.clientY, dy: 0, id: event.pointerId, at: Date.now() };
        });
        document.addEventListener("pointermove", event => {
            if (!s || event.pointerId !== s.id) return;
            s.dy = Math.max(0, event.clientY - s.y);
            if (s.dy < 4) return;
            s.dialog.classList.add("is-dragging");
            s.dialog.style.transform = `translateY(${s.dy}px)`;
        });
        const end = event => {
            if (!s || event.pointerId !== s.id) return;
            const { dialog, dy, at } = s;
            s = null;
            dialog.classList.remove("is-dragging");
            const flick = dy > 40 && Date.now() - at < 250;
            if (dy > 110 || flick) {
                if (dy > 4) markDragged();
                dialog.style.transition = "transform 0.22s ease-in";
                dialog.style.transform = "translateY(100%)";
                setTimeout(() => {
                    dialog.style.transition = "";
                    dialog.style.transform = "";
                    dialog.close();
                }, 220);
            } else if (dy > 0) {
                dialog.animate([{ transform: `translateY(${dy}px)` }, { transform: "none" }],
                    { duration: 320, easing: "cubic-bezier(0.34, 1.56, 0.64, 1)" });
                dialog.style.transform = "";
            }
        };
        document.addEventListener("pointerup", end);
        document.addEventListener("pointercancel", end);
    }

    /* --- Ziehen zum Aktualisieren, wenn Nestwerk als App installiert ist ----- */

    function initPullToRefresh() {
        const standalone = matchMedia("(display-mode: standalone)").matches || navigator.standalone === true;
        if (!standalone || document.querySelector("[data-tablet]")) return;
        let s = null;
        let indicator = null;
        const show = () => {
            if (indicator) return indicator;
            indicator = document.createElement("div");
            indicator.className = "pull-indicator";
            indicator.setAttribute("aria-hidden", "true");
            indicator.innerHTML = '<svg viewBox="0 0 24 24"><path d="M20 12a8 8 0 1 1-2.3-5.6M20 4v5h-5"/></svg>';
            document.body.append(indicator);
            return indicator;
        };
        document.addEventListener("touchstart", event => {
            if (window.scrollY > 0 || event.touches.length !== 1 || document.querySelector("dialog[open]")) return;
            s = { y: event.touches[0].clientY, pull: 0 };
        }, { passive: true });
        document.addEventListener("touchmove", event => {
            if (!s) return;
            s.pull = Math.max(0, Math.min(130, (event.touches[0].clientY - s.y) * 0.5));
            if (s.pull < 6) return;
            const el = show();
            el.style.setProperty("--pull", s.pull + "px");
            const ready = s.pull > 80;
            if (ready !== el.classList.contains("is-ready")) haptic(ready ? 10 : 4);
            el.classList.toggle("is-ready", ready);
        }, { passive: true });
        document.addEventListener("touchend", () => {
            if (!s) return;
            const ready = s.pull > 80;
            s = null;
            if (!indicator) return;
            if (ready) {
                indicator.classList.add("is-loading");
                indicator.style.setProperty("--pull", "70px");
                window.location.reload();
            } else {
                indicator.remove();
                indicator = null;
            }
        }, { passive: true });
    }

    /* Helpers shared with calendar-grid.js */
    window.Nestwerk = Object.freeze({
        showToast, haptic, reloadCalendar, markDragged, closeDialog, reducedMotion, setUpChoreForm
    });

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
        initMotion();
        initRipples();
        initSwipeRows();
        initSwipeNav();
        initToastSwipe();
        initSheetDrag();
        initPullToRefresh();
        initHomeSorting();
        initCalendarDrag();
        initDragClickGuard();
        initUpdateRestart();
        initBlockerWarning();
        initGoalForms();
        initSchoolExamForms();
        initBlockerKindDefaults();
        initIngredientRows();
        initBarcodeScan();
        initMealPlanDrag();
        consumeToasts(document);
    });
})();
