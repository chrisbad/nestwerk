/* Nestwerk – command palette (Strg + K): jump to an area, create something or open a setting by typing a few
   letters. The entries are loaded once from /palette when the palette is opened for the first time. */
(() => {
    "use strict";

    const ENTRIES_URL = "/palette";
    const MAX_RESULTS = 12;

    let entries = null;
    let shown = [];
    let active = 0;

    const dialog = () => document.getElementById("cmd");
    const input = () => document.querySelector("[data-cmd-input]");
    const list = () => document.querySelector("[data-cmd-list]");
    const normalize = text => text.toLocaleLowerCase("de");

    async function load() {
        if (entries) return;
        const response = await fetch(ENTRIES_URL, { headers: { "HX-Request": "true" }, credentials: "same-origin" });
        // parsed into an inert document: nothing in it runs
        const parsed = new DOMParser().parseFromString(await response.text(), "text/html");
        entries = [...parsed.querySelectorAll(".cmd-item")].map(node => ({
            node,
            text: normalize(node.textContent + " " + (node.dataset.search || ""))
        }));
    }

    /** Words typed in any order must all occur in the entry; entries whose label starts with the text come first. */
    function search(query) {
        const words = normalize(query).split(/\s+/).filter(Boolean);
        const hits = entries.filter(entry => words.every(word => entry.text.includes(word)));
        const first = words[0] || "";
        const rank = entry => normalize(entry.node.querySelector(".cmd-label").textContent).startsWith(first) ? 0 : 1;
        return hits.sort((a, b) => rank(a) - rank(b)).slice(0, MAX_RESULTS);
    }

    function render() {
        const target = list();
        target.replaceChildren(...shown.map((entry, index) => {
            const node = entry.node.cloneNode(true);
            node.setAttribute("aria-selected", String(index === active));
            node.classList.toggle("is-active", index === active);
            return node;
        }));
        target.querySelector(".is-active")?.scrollIntoView({ block: "nearest" });
    }

    function refresh() {
        shown = search(input().value);
        active = 0;
        render();
    }

    async function open() {
        const box = dialog();
        if (!box || box.open) return;
        box.showModal();
        input().value = "";
        try {
            await load();
        } catch (e) {
            list().textContent = "Die Liste konnte nicht geladen werden.";
            return;
        }
        refresh();
        input().focus();
    }

    function go(entry) {
        if (!entry) return;
        const url = new URL(entry.node.dataset.path, window.location.origin);
        if (entry.node.dataset.excursion === "true" && !url.searchParams.has("back")) {
            url.searchParams.set("back", window.location.pathname + window.location.search);
        }
        window.location.assign(url.pathname + url.search);
    }

    function onKeydown(event) {
        if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "k") {
            event.preventDefault();
            open();
            return;
        }
        if (!dialog()?.open || !shown.length) return;
        if (event.key === "ArrowDown" || event.key === "ArrowUp") {
            event.preventDefault();
            active = (active + (event.key === "ArrowDown" ? 1 : shown.length - 1)) % shown.length;
            render();
        } else if (event.key === "Enter") {
            event.preventDefault();
            go(shown[active]);
        }
    }

    function onClick(event) {
        if (event.target.closest("[data-open-cmd]")) {
            open();
            return;
        }
        const item = event.target.closest("#cmd .cmd-item");
        if (item) go(shown[[...list().children].indexOf(item)]);
        if (event.target === dialog()) dialog().close();
    }

    document.addEventListener("DOMContentLoaded", () => {
        document.addEventListener("keydown", onKeydown);
        document.addEventListener("click", onClick);
        document.addEventListener("input", event => {
            if (event.target.matches("[data-cmd-input]") && entries) refresh();
        });
    });
})();
