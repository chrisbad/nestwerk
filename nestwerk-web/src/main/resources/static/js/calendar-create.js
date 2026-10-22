/* Nestwerk – dialog "Neu" of the calendar: one dialog to create an event, a chore, a to-do or a time. Tapping or
   spanning a free time in the grid and the toolbar button open it with the time and the person already filled in. */
(() => {
    "use strict";

    const TITLES = { event: "Neuer Termin", chore: "Hausarbeit", todo: "Neues ToDo", time: "Neue Zeit" };
    const KEY = "nestwerk-create-type";
    const DEFAULT_HOUR = 9;

    const dialogOf = root => (root || document).querySelector("#grid-quick");
    const forms = dialog => [...dialog.querySelectorAll("[data-create-form]")];
    const formOf = (dialog, type) => dialog.querySelector(`[data-create-form="${type}"]`);
    const tabs = dialog => [...dialog.querySelectorAll("[data-create-type]")];

    function remember(type) {
        try { sessionStorage.setItem(KEY, type); } catch (e) { /* only a convenience */ }
    }

    function recall() {
        try { return sessionStorage.getItem(KEY); } catch (e) { return null; }
    }

    /** Shows the form of a type; unknown or not allowed types fall back to the first one. */
    function select(dialog, type) {
        const all = tabs(dialog);
        const chosen = all.find(tab => tab.dataset.createType === type) || all[0];
        if (!chosen) return null;
        all.forEach(tab => {
            const on = tab === chosen;
            tab.setAttribute("aria-selected", String(on));
            tab.tabIndex = on ? 0 : -1;
        });
        forms(dialog).forEach(form => { form.hidden = form.dataset.createForm !== chosen.dataset.createType; });
        dialog.querySelector("#grid-quick-title").textContent = TITLES[chosen.dataset.createType];
        return chosen.dataset.createType;
    }

    function setEvent(form, ctx) {
        form.reset();
        const set = (name, value) => { if (form.elements[name]) form.elements[name].value = value; };
        set("allDay", String(ctx.allDay));
        form.classList.toggle("is-allday", ctx.allDay);
        form.querySelectorAll("[data-time-field]").forEach(input => {
            input.required = !ctx.allDay;
            input.disabled = ctx.allDay;
        });
        set("startDate", ctx.startDate);
        set("endDate", ctx.endDate);
        if (!ctx.allDay) {
            set("startTime", ctx.startTime);
            set("endTime", ctx.endTime);
        }
        form.querySelectorAll("[data-quick-member]").forEach(box => {
            box.checked = Boolean(ctx.member) && box.value === ctx.member;
        });
    }

    function setChore(form, ctx) {
        form.reset();
        const date = form.elements.date;
        date.value = date.min && ctx.startDate < date.min ? date.min : ctx.startDate;
        if (!ctx.allDay) form.elements.start.value = ctx.startTime;
        if (ctx.member) form.elements.member.value = ctx.member;
        form.dispatchEvent(new Event("change", { bubbles: true }));
    }

    function setTodo(form, ctx) {
        form.reset();
        form.elements.dueOn.value = ctx.startDate;
        form.querySelectorAll("[data-create-member]").forEach(box => {
            box.checked = Boolean(ctx.member) && box.value === "member:" + ctx.member;
        });
    }

    function setTime(form, ctx) {
        form.reset();
        form.elements.validFrom.value = ctx.startDate;
        const isoDay = (new Date(ctx.startDate + "T12:00:00").getDay() + 6) % 7 + 1;
        form.querySelectorAll("[data-weekday]").forEach(box => { box.checked = +box.dataset.weekday === isoDay; });
        if (!ctx.allDay) {
            form.elements.startTime.value = ctx.startTime;
            form.elements.endTime.value = ctx.endTime;
        }
        if (ctx.member) {
            form.querySelectorAll("[data-create-member]").forEach(box => { box.checked = box.value === ctx.member; });
        }
    }

    /** Fills every form from what was tapped: {allDay, startDate, startTime, endDate, endTime, member}. */
    function prefill(dialog, ctx) {
        [["event", setEvent], ["chore", setChore], ["todo", setTodo], ["time", setTime]].forEach(([type, fill]) => {
            const form = formOf(dialog, type);
            if (form) fill(form, ctx);
        });
    }

    function open(dialog, ctx, type) {
        if (!dialog) return;
        prefill(dialog, ctx);
        const shown = select(dialog, type);
        if (!shown) return;
        dialog.showModal();
        formOf(dialog, shown).querySelector("input:not([type=hidden]):not([disabled]), select")?.focus();
    }

    const pad = number => String(number).padStart(2, "0");

    /** The toolbar button: the shown day, at the next full hour if it is today, else in the morning. */
    function contextFromToolbar(button) {
        const date = button.dataset.date;
        const now = new Date();
        const today = now.getFullYear() + "-" + pad(now.getMonth() + 1) + "-" + pad(now.getDate());
        const hour = date === today ? Math.min(now.getHours() + 1, 22) : DEFAULT_HOUR;
        return {
            allDay: false, startDate: date, startTime: pad(hour) + ":00", endDate: date,
            endTime: pad(hour + 1) + ":00", member: ""
        };
    }

    document.addEventListener("DOMContentLoaded", () => {
        document.addEventListener("click", event => {
            const toolbar = event.target.closest("[data-open-create]");
            if (toolbar) {
                open(dialogOf(), contextFromToolbar(toolbar), recall());
                return;
            }
            const tab = event.target.closest("[data-create-type]");
            if (!tab) return;
            const dialog = tab.closest("dialog");
            const shown = select(dialog, tab.dataset.createType);
            if (shown) {
                remember(shown);
                formOf(dialog, shown).querySelector("input:not([type=hidden]):not([disabled]), select")?.focus();
            }
        });
        document.addEventListener("keydown", event => {
            const tab = event.target.closest?.("[data-create-type]");
            if (!tab || !["ArrowLeft", "ArrowRight"].includes(event.key)) return;
            const all = tabs(tab.closest("dialog"));
            const next = all[(all.indexOf(tab) + (event.key === "ArrowRight" ? 1 : -1) + all.length) % all.length];
            select(tab.closest("dialog"), next.dataset.createType);
            remember(next.dataset.createType);
            next.focus();
            event.preventDefault();
        });
    });

    window.NestwerkCreate = Object.freeze({ open });
})();
