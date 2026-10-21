/* Nestwerk – sortable and filterable tables. A table with data-table="id" is controlled by the tools that point to it
   with data-table-for="id": a search field (data-table-search), chips (data-table-chips="column"), selects
   (data-table-select="column"), a counter (data-table-count) and a reset button (data-table-reset).
   Cells carry data-col and, where it differs from the text, data-value. The state lives in the address
   (?q=…&sort=title,asc&f.area=CALENDAR), so it can be shared; data-table-persist also keeps the last state per device. */
(() => {
    "use strict";

    const STORAGE_PREFIX = "nw.table.";
    const FILTER_PREFIX = "f.";
    const SEPARATOR = ",";
    const collator = new Intl.Collator("de", { numeric: true, sensitivity: "base" });

    const ascending = "ascending";
    const descending = "descending";

    function readStored(id) {
        try { return localStorage.getItem(STORAGE_PREFIX + id); } catch (e) { return null; }
    }

    function writeStored(id, value) {
        try {
            if (value) localStorage.setItem(STORAGE_PREFIX + id, value);
            else localStorage.removeItem(STORAGE_PREFIX + id);
        } catch (e) { /* only a convenience */ }
    }

    const toolsOf = table => [...document.querySelectorAll(`[data-table-for="${table.dataset.table}"]`)];
    const fromTools = (table, selector) => toolsOf(table).flatMap(tools => [...tools.querySelectorAll(selector)]);
    const cellValue = (row, column) => {
        const cell = row.querySelector(`[data-col="${column}"]`);
        return cell ? (cell.dataset.value ?? cell.textContent).trim() : "";
    };

    /* --- State <-> address ---------------------------------------------------------------------------------------- */

    function emptyState() {
        return { q: "", sort: null, dir: ascending, filters: {} };
    }

    function parse(search) {
        const params = new URLSearchParams(search);
        const state = emptyState();
        state.q = params.get("q") || "";
        const [column, direction] = (params.get("sort") || "").split(SEPARATOR);
        if (column) {
            state.sort = column;
            state.dir = direction === "desc" ? descending : ascending;
        }
        params.forEach((value, key) => {
            if (key.startsWith(FILTER_PREFIX) && value) {
                state.filters[key.slice(FILTER_PREFIX.length)] = value.split(SEPARATOR);
            }
        });
        return state;
    }

    function serialize(state) {
        const params = new URLSearchParams();
        if (state.q) params.set("q", state.q);
        if (state.sort) params.set("sort", state.sort + SEPARATOR + (state.dir === descending ? "desc" : "asc"));
        Object.entries(state.filters).forEach(([column, values]) => {
            if (values.length) params.set(FILTER_PREFIX + column, values.join(SEPARATOR));
        });
        return params.toString();
    }

    const isEmpty = state => !state.q && !state.sort && Object.values(state.filters).every(v => !v.length);

    /* --- Applying the state to the table ------------------------------------------------------------------------- */

    function matches(row, state) {
        const text = state.q.toLocaleLowerCase("de");
        const haystack = (row.textContent + " " + (row.dataset.search || "")).toLocaleLowerCase("de");
        if (text && !text.split(/\s+/).every(word => haystack.includes(word))) return false;
        return Object.entries(state.filters).every(([column, values]) =>
            !values.length || values.includes(cellValue(row, column)));
    }

    function sortRows(table, state) {
        const body = table.tBodies[0];
        const rows = [...body.rows];
        if (!rows.every(row => row.dataset.index)) rows.forEach((row, i) => { row.dataset.index = String(i); });
        const header = table.querySelector(`th[data-col="${state.sort}"]`);
        const numeric = header?.dataset.sort === "number";
        const factor = state.dir === descending ? -1 : 1;
        rows.sort((a, b) => {
            if (!state.sort) return a.dataset.index - b.dataset.index;
            const left = cellValue(a, state.sort);
            const right = cellValue(b, state.sort);
            const order = numeric ? parseFloat(left) - parseFloat(right) : collator.compare(left, right);
            return (order || a.dataset.index - b.dataset.index) * factor;
        });
        rows.forEach(row => body.append(row));
    }

    function apply(table, state) {
        sortRows(table, state);
        const rows = [...table.tBodies[0].rows];
        let shown = 0;
        rows.forEach(row => {
            const visible = matches(row, state);
            row.hidden = !visible;
            if (visible) shown++;
        });
        table.querySelectorAll("th[data-sort]:not([data-sort='none'])").forEach(th => {
            th.setAttribute("aria-sort", th.dataset.col === state.sort ? state.dir : "none");
        });
        fromTools(table, "[data-table-search]").forEach(input => { if (input.value !== state.q) input.value = state.q; });
        fromTools(table, "[data-table-chips]").forEach(group => {
            const selected = state.filters[group.dataset.tableChips] || [];
            group.querySelectorAll("[data-chip-value]").forEach(chip =>
                chip.setAttribute("aria-pressed", String(selected.includes(chip.dataset.chipValue))));
        });
        fromTools(table, "[data-table-select]").forEach(select => {
            select.value = (state.filters[select.dataset.tableSelect] || [""])[0];
        });
        fromTools(table, "[data-table-count]").forEach(counter => {
            counter.textContent = shown === rows.length ? `${rows.length} Einträge` : `${shown} von ${rows.length}`;
        });
        fromTools(table, "[data-table-reset]").forEach(button => { button.hidden = isEmpty(state); });
        const none = document.querySelector(`[data-table-empty="${table.dataset.table}"]`);
        if (none) none.hidden = shown > 0;
        table.hidden = shown === 0 && Boolean(none);
    }

    function store(table, state) {
        const query = serialize(state);
        const url = window.location.pathname + (query ? "?" + query : "") + window.location.hash;
        history.replaceState(history.state, "", url);
        if ("tablePersist" in table.dataset) writeStored(table.dataset.table, query);
    }

    function update(table, change) {
        const state = table._state;
        change(state);
        apply(table, state);
        store(table, state);
    }

    /* --- Events -------------------------------------------------------------------------------------------------- */

    function toggleChip(state, column, value) {
        const values = state.filters[column] || [];
        state.filters[column] = values.includes(value) ? values.filter(v => v !== value) : [...values, value];
    }

    function cycleSort(state, column, defaultDirection) {
        if (state.sort !== column) {
            state.sort = column;
            state.dir = defaultDirection;
        } else if (state.dir === defaultDirection) {
            state.dir = defaultDirection === ascending ? descending : ascending;
        } else {
            state.sort = null;
            state.dir = ascending;
        }
    }

    const tableFor = element => {
        const owner = element.closest("[data-table-for]");
        return owner ? document.querySelector(`table[data-table="${owner.dataset.tableFor}"]`) : null;
    };

    function onInput(event) {
        const search = event.target.closest("[data-table-search]");
        const table = search && tableFor(search);
        if (table) update(table, state => { state.q = search.value.trim(); });
    }

    function onChange(event) {
        const select = event.target.closest("[data-table-select]");
        const table = select && tableFor(select);
        if (table) update(table, state => { state.filters[select.dataset.tableSelect] = select.value ? [select.value] : []; });
    }

    function openRow(row, event) {
        const url = new URL(row.dataset.open, window.location.origin);
        url.searchParams.set("back", window.location.pathname + window.location.search);
        if (event.ctrlKey || event.metaKey || event.button === 1) window.open(url, "_blank", "noopener");
        else window.location.assign(url.pathname + url.search);
    }

    function onClick(event) {
        const chip = event.target.closest("[data-table-chips] [data-chip-value]");
        const chipTable = chip && tableFor(chip);
        if (chipTable) {
            update(chipTable, state => toggleChip(state, chip.closest("[data-table-chips]").dataset.tableChips, chip.dataset.chipValue));
            return;
        }
        const reset = event.target.closest("[data-table-reset]");
        const resetTable = reset && tableFor(reset);
        if (resetTable) {
            update(resetTable, state => Object.assign(state, emptyState()));
            return;
        }
        const header = event.target.closest("table[data-table] th[data-sort]:not([data-sort='none'])");
        if (header) {
            const table = header.closest("table[data-table]");
            update(table, state => cycleSort(state, header.dataset.col, header.dataset.sort === "number" ? descending : ascending));
            return;
        }
        const row = event.target.closest("table[data-table] tbody tr[data-open]");
        if (row && !event.target.closest("a, button, input, select, label")) openRow(row, event);
    }

    function onKeydown(event) {
        const sortable = event.target.closest("table[data-table] th[data-sort]:not([data-sort='none'])");
        if (sortable && (event.key === "Enter" || event.key === " ")) {
            event.preventDefault();
            sortable.click();
            return;
        }
        const row = event.target.closest("table[data-table] tbody tr[data-open]");
        if (row && event.target === row && event.key === "Enter") openRow(row, event);
    }

    /* --- Set-up -------------------------------------------------------------------------------------------------- */

    function initialState(table) {
        const fromAddress = parse(window.location.search);
        if (!isEmpty(fromAddress) || !("tablePersist" in table.dataset)) {
            if (isEmpty(fromAddress) && table.dataset.sortDefault) fromAddress.sort = table.dataset.sortDefault;
            return fromAddress;
        }
        const stored = parse(readStored(table.dataset.table) || "");
        if (isEmpty(stored) && table.dataset.sortDefault) stored.sort = table.dataset.sortDefault;
        return stored;
    }

    function setUp(table) {
        if (table._state) return;
        table._state = initialState(table);
        table.querySelectorAll("th[data-sort]:not([data-sort='none'])").forEach(th => { th.tabIndex = 0; });
        table.tBodies[0].querySelectorAll("tr[data-open]").forEach(row => { row.tabIndex = 0; });
        apply(table, table._state);
    }

    function setUpAll() {
        document.querySelectorAll("table[data-table]").forEach(setUp);
    }

    document.addEventListener("DOMContentLoaded", () => {
        document.addEventListener("input", onInput);
        document.addEventListener("change", onChange);
        document.addEventListener("click", onClick);
        document.addEventListener("keydown", onKeydown);
        document.addEventListener("htmx:afterSettle", setUpAll);
        window.addEventListener("pageshow", setUpAll);
        setUpAll();
    });
})();
