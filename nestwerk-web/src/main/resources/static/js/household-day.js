/* Nestwerk – day of the household plan: chips choose whose tasks are shown. By default it is the own person; more
   people can be added. The choice is kept per device and shows in the address (?who=1,2) so it can be shared. */
(() => {
    "use strict";

    const KEY = "nestwerk-household-who";
    const OPEN = "open";
    const SEPARATOR = ",";

    const root = () => document.querySelector("[data-household-day]");
    const chipsOf = day => [...day.querySelectorAll("[data-who]")];
    const allIds = day => chipsOf(day).map(chip => chip.dataset.who);

    function fromAddress() {
        const who = new URLSearchParams(window.location.search).get("who");
        return who ? who.split(SEPARATOR).filter(Boolean) : null;
    }

    function stored() {
        try {
            const value = JSON.parse(localStorage.getItem(KEY) || "null");
            return Array.isArray(value) ? value.map(String) : null;
        } catch (e) {
            return null;
        }
    }

    function remember(selected) {
        try { localStorage.setItem(KEY, JSON.stringify(selected)); } catch (e) { /* only a convenience */ }
        const url = new URL(window.location.href);
        url.searchParams.set("who", selected.join(SEPARATOR));
        history.replaceState(history.state, "", url.pathname + url.search + url.hash);
    }

    /** Own person if there is one, otherwise everybody. */
    function initial(day) {
        const own = day.dataset.ownMember;
        const known = allIds(day);
        const wanted = fromAddress() ?? stored() ?? (own ? [own] : known);
        const valid = wanted.filter(id => known.includes(id));
        return valid.length ? valid : (own && known.includes(own) ? [own] : known);
    }

    function apply(day, selected) {
        chipsOf(day).forEach(chip => chip.setAttribute("aria-pressed", String(selected.includes(chip.dataset.who))));
        let shown = 0;
        let listed = 0;
        day.querySelectorAll(".chore-chip[data-member]").forEach(item => {
            const visible = selected.includes(item.dataset.member);
            item.hidden = !visible;
            listed++;
            if (visible) shown++;
        });
        day.querySelectorAll(".chore-section").forEach(section => {
            const items = section.querySelectorAll(".chore-chip[data-member]");
            if (items.length) section.hidden = ![...items].some(item => !item.hidden);
        });
        const empty = day.querySelector("[data-who-empty]");
        if (empty) empty.hidden = !(listed > 0 && shown === 0);
    }

    function toggle(day, id) {
        const current = day._selected;
        day._selected = current.includes(id) ? current.filter(other => other !== id) : [...current, id];
        if (!day._selected.length) day._selected = allIds(day);
        apply(day, day._selected);
        remember(day._selected);
    }

    function setUp() {
        const day = root();
        if (!day || day._selected) return;
        day._selected = initial(day);
        apply(day, day._selected);
    }

    document.addEventListener("DOMContentLoaded", () => {
        document.addEventListener("click", event => {
            const chip = event.target.closest("[data-household-day] [data-who]");
            const all = event.target.closest("[data-household-day] [data-who-all]");
            const day = (chip || all)?.closest("[data-household-day]");
            if (!day) return;
            if (all) {
                day._selected = allIds(day);
                apply(day, day._selected);
                remember(day._selected);
            } else {
                toggle(day, chip.dataset.who);
            }
        });
        document.addEventListener("htmx:afterSettle", setUp);
        setUp();
    });
})();
