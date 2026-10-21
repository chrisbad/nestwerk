/* Nestwerk – quick dialog "Hausarbeit" of the calendar: one form to plan a chore or to book it as done. The mode
   decides which fields are sent and where the form goes; the chosen chore sets the minutes of the effort levels. */
(() => {
    "use strict";

    const EFFORTS = { EASY: "easy", MEDIUM: "medium", HARD: "hard" };

    const formOf = element => element.closest("form[data-chore-quick]");

    function setMode(form) {
        const book = form.querySelector("[data-chore-mode]:checked").value === "book";
        form.querySelectorAll("[data-chore-panel]").forEach(panel => {
            const active = panel.dataset.chorePanel === (book ? "book" : "plan");
            panel.hidden = !active;
            panel.querySelectorAll("input, select").forEach(field => { field.disabled = !active; });
        });
        form.action = book ? form.dataset.bookUrl : form.dataset.planUrl;
        form.querySelector("[data-chore-submit]").textContent = book ? "Buchen" : "Einplanen";
    }

    /** The minutes of the three effort levels and the default duration follow the chosen chore. */
    function setChore(form) {
        const option = form.querySelector("[data-chore-select]").selectedOptions[0];
        if (!option) return;
        Object.entries(EFFORTS).forEach(([effort, key]) => {
            const radio = form.querySelector(`input[name=effort][value=${effort}]`);
            if (!radio) return;
            radio.dataset.minutes = option.dataset[key];
            radio.closest(".effort-choice")?.querySelector(".effort-minutes")?.replaceChildren(option.dataset[key] + " Min");
        });
        const minutes = form.querySelector("[data-book-minutes]");
        const medium = form.querySelector("input[name=effort][value=MEDIUM]");
        if (minutes && medium) {
            delete minutes.dataset.touched;
            medium.checked = true;
            minutes.value = option.dataset.medium;
        }
    }

    function setUpAll() {
        document.querySelectorAll("form[data-chore-quick]").forEach(form => {
            setMode(form);
            setChore(form);
        });
    }

    document.addEventListener("DOMContentLoaded", () => {
        document.addEventListener("change", event => {
            const form = formOf(event.target);
            if (!form) return;
            if (event.target.matches("[data-chore-mode]")) setMode(form);
            if (event.target.matches("[data-chore-select]")) setChore(form);
        });
        // the calendar toolbar arrives anew with every swap
        document.addEventListener("htmx:afterSettle", setUpAll);
        setUpAll();
    });
})();
