/* Nestwerk – context menu of calendar entries: right click (or the menu key) on an entry shows what the viewer may do
   with it right away. Touch keeps its gestures: tapping opens the dialog with its quick action bar, long press moves. */
(() => {
    "use strict";

    const ENTRY = "#calendar [data-sheet-url]";
    const SHEET_URL = /\/calendar\/(events|chores|todos)\/(\d+)\/sheet/;
    const EDGE = 8;

    let menu = null;
    let opener = null;
    let lastForm = null;

    function close(restoreFocus) {
        if (!menu) return;
        menu.remove();
        menu = null;
        if (restoreFocus) opener?.focus?.();
        opener = null;
    }

    async function load(entry) {
        const match = SHEET_URL.exec(entry.dataset.sheetUrl || "");
        if (!match) return [];
        const query = entry.dataset.recurrenceId ? "?occurrence=" + encodeURIComponent(entry.dataset.recurrenceId) : "";
        const response = await fetch(`/calendar/items/${match[1]}/${match[2]}/actions${query}`,
            { headers: { "Accept": "application/json" } });
        if (!response.ok) throw new Error("Die Aktionen konnten nicht geladen werden.");
        return response.json();
    }

    function place(panel, x, y) {
        panel.style.left = "0px";
        panel.style.top = "0px";
        const box = panel.getBoundingClientRect();
        const left = Math.max(EDGE, Math.min(x, window.innerWidth - box.width - EDGE));
        const top = Math.max(EDGE, Math.min(y, window.innerHeight - box.height - EDGE));
        panel.style.left = left + "px";
        panel.style.top = top + "px";
    }

    function build(entry, actions) {
        const panel = document.createElement("div");
        panel.className = "context-menu";
        panel.setAttribute("role", "menu");
        panel.setAttribute("aria-label", entry.dataset.title || "Aktionen");
        actions.forEach(action => {
            const item = document.createElement("button");
            item.type = "button";
            item.className = "menu-item" + (action.danger ? " menu-item-danger" : "");
            item.setAttribute("role", "menuitem");
            item.tabIndex = -1;
            item.textContent = action.label;
            item.addEventListener("click", () => run(entry, action));
            panel.append(item);
        });
        return panel;
    }

    /** Opens the dialog, or posts the action through a form so that confirmation and reloading work as everywhere. */
    function run(entry, action) {
        close(false);
        if (!action.url) {
            window.NestwerkCalendar?.openSheet(entry.dataset.sheetUrl);
            return;
        }
        const form = document.createElement("form");
        form.method = "post";
        form.action = action.url;
        form.hidden = true;
        form.dataset.gridForm = "quick";
        if (action.confirm) {
            form.dataset.confirm = action.confirm;
            if (action.confirmLabel) form.dataset.confirmLabel = action.confirmLabel;
            if (action.danger) form.dataset.confirmTone = "danger";
        }
        Object.entries(action.params || {}).forEach(([name, value]) => {
            const field = document.createElement("input");
            field.type = "hidden";
            field.name = name;
            field.value = value;
            form.append(field);
        });
        // stays until the next action: a confirmation dialog submits it again after it was answered
        lastForm?.remove();
        lastForm = form;
        document.body.append(form);
        form.requestSubmit();
    }

    async function open(entry, x, y) {
        close(false);
        let actions;
        try {
            actions = await load(entry);
        } catch (error) {
            window.Nestwerk?.showToast(error.message, "error");
            return;
        }
        if (!actions.length) return;
        opener = entry;
        menu = build(entry, actions);
        document.body.append(menu);
        place(menu, x, y);
        menu.querySelector('[role="menuitem"]').focus();
    }

    function onContextMenu(event) {
        const entry = event.target.closest(ENTRY);
        if (!entry || event.target.closest("dialog")) return;
        // a long press with a finger is "move", not a menu
        if (event.pointerType === "touch" || event.pointerType === "pen") return;
        event.preventDefault();
        const box = entry.getBoundingClientRect();
        const fromKeyboard = event.clientX === 0 && event.clientY === 0;
        open(entry, fromKeyboard ? box.left : event.clientX, fromKeyboard ? box.bottom : event.clientY);
    }

    function onKeydown(event) {
        if (!menu) return;
        const items = [...menu.querySelectorAll('[role="menuitem"]')];
        const index = items.indexOf(document.activeElement);
        const move = target => { items[(target + items.length) % items.length].focus(); event.preventDefault(); };
        switch (event.key) {
            case "ArrowDown": move(index + 1); break;
            case "ArrowUp": move(index - 1); break;
            case "Home": move(0); break;
            case "End": move(items.length - 1); break;
            case "Escape": close(true); event.preventDefault(); break;
            case "Tab": close(false); break;
            default:
        }
    }

    document.addEventListener("DOMContentLoaded", () => {
        document.addEventListener("contextmenu", onContextMenu, true);
        document.addEventListener("keydown", onKeydown);
        document.addEventListener("pointerdown", event => {
            if (menu && !menu.contains(event.target)) close(false);
        }, true);
        window.addEventListener("scroll", () => close(false), true);
        window.addEventListener("resize", () => close(false));
    });
})();
