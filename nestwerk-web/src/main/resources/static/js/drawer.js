/* Nestwerk – side panel for details: an element with data-drawer-url loads that page fragment into the one drawer
   of the page instead of every row bringing its own dialog. The browser's back button closes the drawer. */
(() => {
    "use strict";

    const dialog = () => document.getElementById("drawer");
    const body = () => document.querySelector("[data-drawer-body]");

    let opener = null;

    function show(nodes, label) {
        const target = body();
        target.replaceChildren(...nodes);
        window.htmx?.process(target);
        dialog().setAttribute("aria-label", label || "Details");
    }

    function message(text, kind) {
        const paragraph = document.createElement("p");
        paragraph.className = kind;
        paragraph.textContent = text;
        return [paragraph];
    }

    async function open(trigger) {
        const box = dialog();
        if (!box) return;
        opener = trigger;
        show(message("Wird geladen …", "drawer-loading muted"), trigger.dataset.drawerTitle);
        if (!box.open) {
            box.showModal();
            history.pushState({ nwDrawer: true }, "");
        }
        try {
            const response = await fetch(trigger.dataset.drawerUrl, { credentials: "same-origin" });
            if (!response.ok) throw new Error(String(response.status));
            // parsed into an inert document first: nothing in it runs until it is part of the page
            const parsed = new DOMParser().parseFromString(await response.text(), "text/html");
            show([...parsed.body.childNodes].map(node => document.importNode(node, true)), trigger.dataset.drawerTitle);
            box.querySelector("input:not([type=hidden]), select, textarea")?.focus();
        } catch (e) {
            show(message("Das ging leider nicht. Bitte versuche es noch einmal.", "drawer-error notice notice-error"),
                trigger.dataset.drawerTitle);
        }
    }

    function onClick(event) {
        const trigger = event.target.closest("[data-drawer-url]");
        if (!trigger) return;
        event.preventDefault();
        open(trigger);
    }

    document.addEventListener("DOMContentLoaded", () => {
        document.addEventListener("click", onClick);
        const box = dialog();
        box?.addEventListener("close", () => {
            body().replaceChildren();
            if (history.state?.nwDrawer) history.back();
            opener?.focus?.();
            opener = null;
        });
        window.addEventListener("popstate", () => { if (box?.open) box.close(); });
    });
})();
