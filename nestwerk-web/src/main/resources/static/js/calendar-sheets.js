/* Nestwerk – dialogs of calendar entries (events, chores, to-dos) in every calendar view and their JSON actions. */
(() => {
    "use strict";

    const helpers = () => window.Nestwerk;

    /** Posts a form or plain parameters; answers the JSON of the action or fails with its message. */
    async function post(url, params) {
        const token = document.querySelector('meta[name="_csrf"]')?.content;
        const header = document.querySelector('meta[name="_csrf_header"]')?.content;
        const headers = { "Accept": "application/json", "Content-Type": "application/x-www-form-urlencoded" };
        if (token && header) headers[header] = token;
        const response = await fetch(url, { method: "POST", headers, body: new URLSearchParams(params) });
        let body = {};
        try { body = await response.json(); } catch (e) { /* no JSON, e.g. login page */ }
        if (!response.ok) throw new Error(body.message || "Das hat leider nicht geklappt.");
        return body;
    }

    /** A toast, with "Rückgängig" if {@code undo} is given; warnings of blockers are shown in the same toast. */
    function report(result, undo) {
        const warnings = result.warnings || [];
        const text = warnings.length ? result.message + " Achtung: " + warnings.join(" ") : result.message;
        const region = document.querySelector("[data-toasts]");
        if (!region || !undo) {
            helpers().showToast(text, warnings.length ? "error" : "success");
            return;
        }
        const toast = document.createElement("div");
        toast.className = "toast toast-action" + (warnings.length ? " toast-warn" : "");
        toast.setAttribute("role", warnings.length ? "alert" : "status");
        const message = document.createElement("span");
        message.textContent = text;
        const button = document.createElement("button");
        button.type = "button";
        button.className = "toast-undo";
        button.textContent = "Rückgängig";
        button.addEventListener("click", async () => {
            toast.remove();
            try {
                await undo();
                helpers().showToast("Rückgängig gemacht.", "success");
            } catch (error) {
                helpers().showToast(error.message, "error");
            }
        });
        toast.append(message, button);
        region.append(toast);
        if (warnings.length) helpers().haptic([12, 60, 12]);
        setTimeout(() => {
            if (!toast.isConnected || toast.classList.contains("is-dragging")) return;
            toast.classList.add("is-leaving");
            toast.addEventListener("animationend", () => toast.remove(), { once: true });
            setTimeout(() => toast.remove(), 400);
        }, warnings.length ? 9000 : 6000);
    }

    /* --- Opening a dialog ---------------------------------------------------------------------------------------- */

    async function openSheet(url) {
        const dialog = document.getElementById("calendar-sheet");
        if (!dialog || !url) return;
        try {
            const response = await fetch(url, { headers: { "Accept": "text/html" } });
            if (!response.ok) throw new Error("Der Eintrag konnte nicht geladen werden.");
            const body = dialog.querySelector("[data-sheet-body]");
            body.innerHTML = await response.text();
            syncScope(body);
            body.querySelectorAll("[data-chore-form]").forEach(form => helpers().setUpChoreForm(form));
            dialog.showModal();
        } catch (error) {
            helpers().showToast(error.message, "error");
        }
    }

    /** Category and members belong to the whole series; the days of a series stay when it moves. */
    function syncScope(sheet) {
        const chosen = sheet.querySelector("[data-sheet-scope]:checked")?.value;
        if (!chosen) return;
        sheet.querySelectorAll("[data-series-only] select, [data-series-only] input").forEach(input => {
            input.disabled = chosen === "OCCURRENCE";
        });
        sheet.querySelectorAll('input[type="date"]').forEach(input => { input.readOnly = chosen === "SERIES"; });
        sheet.querySelectorAll("[data-scope-hint]").forEach(hint => { hint.hidden = hint.dataset.scopeHint !== chosen; });
    }

    function selectTab(tab) {
        const list = tab.closest("[data-tabs]");
        const sheet = list.parentElement;
        list.querySelectorAll('[role="tab"]').forEach(other => {
            const selected = other === tab;
            other.setAttribute("aria-selected", String(selected));
            other.tabIndex = selected ? 0 : -1;
            sheet.querySelector(`[data-tab-panel="${other.dataset.tab}"]`).hidden = !selected;
        });
        tab.focus();
    }

    /** The current calendar view, so that "Alle Details" leads back to exactly this view and day. */
    const here = () => window.location.pathname + window.location.search;

    function onClick(event) {
        if (event.defaultPrevented) return;
        const tab = event.target.closest('[data-tabs] [role="tab"]');
        if (tab) {
            selectTab(tab);
            return;
        }
        const details = event.target.closest("a[data-keep-back]");
        if (details) {
            const url = new URL(details.href, window.location.origin);
            url.searchParams.set("back", here());
            details.href = url.pathname + url.search;
            return;
        }
        // the time grid opens its entries itself, after telling taps from drags
        const entry = event.target.closest("#calendar [data-sheet-url]");
        if (!entry || entry.closest("[data-time-grid] .tgrid")) return;
        event.preventDefault();
        openSheet(entry.dataset.sheetUrl);
    }

    function onKeydown(event) {
        const tab = event.target.closest?.('[data-tabs] [role="tab"]');
        if (!tab || !["ArrowLeft", "ArrowRight"].includes(event.key)) return;
        const tabs = [...tab.closest("[data-tabs]").querySelectorAll('[role="tab"]')];
        const step = event.key === "ArrowRight" ? 1 : -1;
        selectTab(tabs[(tabs.indexOf(tab) + step + tabs.length) % tabs.length]);
        event.preventDefault();
    }

    /* --- Saving: every dialog form answers with JSON; the calendar stays on its view and day ---------------------- */

    async function onSubmit(event) {
        const form = event.target.closest("form[data-grid-form]");
        if (!form) return;
        // the confirmation dialog asks first and submits again afterwards
        if (form.dataset.confirm && form.dataset.confirmed !== "true") return;
        event.preventDefault();
        delete form.dataset.confirmed;
        const button = event.submitter || form.querySelector('[type="submit"]');
        const data = new FormData(form, event.submitter?.name ? event.submitter : undefined);
        if (button) button.disabled = true;
        try {
            const result = await post(form.action, data);
            const dialog = form.closest("dialog");
            if (dialog?.open) dialog.close();
            const undo = form.dataset.gridForm === "create" && result.eventId
                ? () => post(`/calendar/actions/events/${result.eventId}/delete`, {}).then(() => helpers().reloadCalendar())
                : null;
            report({ ...result, message: result.message + outsideNote(form) }, undo);
            helpers().reloadCalendar();
        } catch (error) {
            helpers().showToast(error.message, "error");
        } finally {
            if (button) button.disabled = false;
        }
    }

    /** A hint when the changed entry now lies outside the shown period; the calendar does not jump there. */
    function outsideNote(form) {
        const date = form.elements.startDate?.value || form.elements.date?.value || form.elements.dueOn?.value;
        const grid = document.querySelector("[data-time-grid]");
        if (!date || !grid) return "";
        const shown = [...grid.querySelectorAll(".tgrid-col")].map(column => column.dataset.date);
        if (shown.includes(date)) return "";
        const label = new Date(date + "T12:00:00").toLocaleDateString("de-DE",
            { weekday: "short", day: "numeric", month: "numeric" });
        return " Jetzt am " + label + ".";
    }

    window.NestwerkCalendar = Object.freeze({ post, report, openSheet });

    document.addEventListener("DOMContentLoaded", () => {
        document.addEventListener("click", onClick, true);
        document.addEventListener("keydown", onKeydown);
        document.addEventListener("submit", onSubmit);
        document.addEventListener("change", event => {
            if (event.target.matches("[data-sheet-scope]")) syncScope(event.target.closest("[data-sheet-body]"));
        });
    });
})();
