/* Nestwerk – calendar layers: events, to-dos and chores can be hidden one by one. The choice is kept per device and
   applies to every calendar view; the old to-do switch is taken over. */
(() => {
    "use strict";

    const KEY = "nestwerk-cal-layers";
    const LEGACY_TODO_KEY = "nestwerk-cal-todos";
    const LAYERS = ["events", "todos", "chores"];

    function read() {
        const shown = Object.fromEntries(LAYERS.map(layer => [layer, true]));
        try {
            Object.assign(shown, JSON.parse(localStorage.getItem(KEY) || "{}"));
            if (localStorage.getItem(LEGACY_TODO_KEY) === "hidden" && localStorage.getItem(KEY) === null) {
                shown.todos = false;
            }
        } catch (e) { /* without storage everything is shown */ }
        return shown;
    }

    function write(shown) {
        try { localStorage.setItem(KEY, JSON.stringify(shown)); } catch (e) { /* only a convenience */ }
    }

    function apply() {
        const calendar = document.getElementById("calendar");
        if (!calendar) return;
        const shown = read();
        LAYERS.forEach(layer => calendar.classList.toggle("hide-" + layer, shown[layer] === false));
        document.querySelectorAll("[data-layer]").forEach(button =>
            button.setAttribute("aria-pressed", String(shown[button.dataset.layer] !== false)));
    }

    document.addEventListener("DOMContentLoaded", () => {
        document.addEventListener("click", event => {
            const button = event.target.closest("[data-layer]");
            if (!button) return;
            const shown = read();
            shown[button.dataset.layer] = shown[button.dataset.layer] === false;
            write(shown);
            apply();
        });
        document.addEventListener("htmx:afterSwap", apply);
        apply();
    });
})();
