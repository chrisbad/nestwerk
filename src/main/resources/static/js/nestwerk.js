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
        let pending = null;
        document.addEventListener("submit", event => {
            const form = event.target;
            if (!form.matches("form[data-confirm]") || form.dataset.confirmed === "true") return;
            event.preventDefault();
            pending = form;
            dialog.querySelector("[data-confirm-text]").textContent = form.dataset.confirm;
            const ok = dialog.querySelector("[data-confirm-ok]");
            if (form.dataset.confirmLabel) ok.textContent = form.dataset.confirmLabel;
            dialog.showModal();
            ok.focus();
        });
        dialog.querySelector("[data-confirm-ok]").addEventListener("click", () => {
            if (!pending) return;
            pending.dataset.confirmed = "true";
            dialog.close();
            pending.requestSubmit();
        });
        dialog.addEventListener("close", () => {
            if (pending && pending.dataset.confirmed !== "true") pending = null;
        });
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
        initHtmx();
        initGreeting();
        initEventForm();
        initAutoSubmit();
        consumeToasts(document);
    });
})();
