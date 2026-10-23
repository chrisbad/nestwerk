/* Nestwerk – day view in lanes: time runs from left to right, one lane per person. Stacks overlapping entries by their
   rendered size so no title is cut off, creates entries by tapping a free time, keeps the slot size and moves or
   resizes entries by dragging (saved through the shared logic of calendar-grid.js). */
(() => {
    "use strict";

    const SLOT_KEY = "nestwerk-calendar-slot";
    const DAY_MINUTES = 1440;
    const DEFAULT_MINUTES = 60;
    const FIRST_HOUR_SHOWN = 7;
    const ENTRY_GAP_PX = 6;
    const ROUNDING_TOLERANCE_PX = 1;
    const LANE_PADDING_PX = 6;
    const LONG_PRESS_MS = 300;
    const TOUCH_TOLERANCE_PX = 8;
    const MOUSE_TOLERANCE_PX = 4;
    const CLICK_GUARD_MS = 350;
    const SCROLL_EDGE_PX = 48;
    const SCROLL_STEP_PX = 12;
    const DRAG_LABEL_OFFSET_PX = 44;
    const DRAG_LABEL_OFFSET_TOUCH_PX = 84;

    const helpers = () => window.Nestwerk;
    const grid = () => window.NestwerkGrid;
    const scrollerOf = root => root.querySelector("[data-lanes-scroll]");

    const lanesOf = root => [...root.querySelectorAll("[data-lane]")];

    /** The remembered slot length in minutes; the old words "fine" and "coarse" still count. */
    function readSlot(root) {
        let stored = null;
        try {
            stored = localStorage.getItem(SLOT_KEY);
        } catch (e) { /* the default applies */ }
        const minutes = stored === "fine" ? +root.dataset.slotFine : stored === "coarse" ? +root.dataset.slotCoarse
            : +stored;
        const offered = [...root.querySelectorAll("[data-slot-select] option")].map(option => +option.value);
        return offered.includes(minutes) ? minutes : +root.dataset.slotCoarse;
    }

    function applySlot(root, minutes) {
        root.dataset.slot = String(minutes);
        root.querySelectorAll("[data-slot-select]").forEach(select => { select.value = String(minutes); });
    }

    const slotMinutes = root => +root.dataset.slot || +root.dataset.slotCoarse;

    /** Puts entries that would overlap on screen (including their minimum width) into rows below each other.
        Entries that follow each other directly stay in one row; their width already leaves a small visual gap. */
    function stack(lane) {
        const entries = [...lane.querySelectorAll("[data-lane-entry]")]
            .sort((a, b) => a.offsetLeft - b.offsetLeft);
        const rows = [];
        entries.forEach(entry => {
            let row = rows.find(r => r.end <= entry.offsetLeft + ROUNDING_TOLERANCE_PX);
            if (!row) {
                row = { end: 0, height: 0, items: [] };
                rows.push(row);
            }
            row.end = entry.offsetLeft + entry.offsetWidth;
            row.height = Math.max(row.height, entry.offsetHeight);
            row.items.push(entry);
        });
        let top = LANE_PADDING_PX;
        rows.forEach(row => {
            row.items.forEach(entry => { entry.style.top = top + "px"; });
            top += row.height + ENTRY_GAP_PX;
        });
        lane.style.setProperty("--lane-height", (rows.length ? top : LANE_PADDING_PX * 2 + ENTRY_GAP_PX) + "px");
    }

    function layout(root) {
        lanesOf(root).forEach(stack);
    }

    function minuteAt(track, x) {
        const box = track.getBoundingClientRect();
        return Math.max(0, Math.min(DAY_MINUTES, (x - box.left) / box.width * DAY_MINUTES));
    }

    function onClick(event) {
        const root = event.target.closest("[data-lanes]");
        if (!root || event.target.closest("dialog")) return;
        if (Date.now() - lastDragEnd < CLICK_GUARD_MS) {
            event.preventDefault();
            event.stopPropagation();
            return;
        }
        const track = event.target.closest("[data-lane-track]");
        if (!track || event.target.closest(".occ") || root.dataset.canCreate !== "true") return;
        const slot = slotMinutes(root);
        const start = Math.floor(minuteAt(track, event.clientX) / slot) * slot;
        const lane = track.closest("[data-lane]");
        window.NestwerkGrid.openQuickAt(root, lane.dataset.date, start, start + DEFAULT_MINUTES, lane.dataset.member);
    }

    /* --- Gestures: move to another time or person, resize at the edges ------------------------------------------- */

    let gesture = null;
    let drag = null;
    let lastDragEnd = 0;
    let keptScrollLeft = null;

    const memberOf = element => element?.dataset.member ?? "";
    const tracksOf = root => [...root.querySelectorAll("[data-lane-track]")];

    function deselect(root) {
        root?.querySelectorAll(".lanes-entry.is-selected").forEach(el => el.classList.remove("is-selected"));
    }

    /** The lane under the pointer; above the first or below the last lane it is the first or last one. */
    function trackAt(root, y) {
        const tracks = tracksOf(root);
        return tracks.find(track => {
            const box = track.getBoundingClientRect();
            return y >= box.top && y < box.bottom;
        }) || (y < tracks[0].getBoundingClientRect().top ? tracks[0] : tracks.at(-1));
    }

    function onPointerDown(event) {
        const root = event.target.closest("[data-lanes]");
        if (!root || drag || event.button > 0 || !event.isPrimary
            || event.target.closest("dialog, .cal-toolbar, .cal-legend-row, .lanes-label, .lanes-hint")) return;
        const item = event.target.closest("[data-lane-entry]");
        if (!item || !item.classList.contains("is-selected")) deselect(root);
        if (!item || item.dataset.editable !== "true") return;
        const handle = event.target.closest("[data-handle]");
        gesture = {
            root, item, handle, id: event.pointerId, touch: event.pointerType !== "mouse",
            x0: event.clientX, y0: event.clientY, x: event.clientX, y: event.clientY, timer: 0
        };
        if (!gesture.touch) return;
        if (handle && item.classList.contains("is-selected")) {
            begin();
            return;
        }
        gesture.timer = setTimeout(() => { if (gesture) begin(); }, LONG_PRESS_MS);
    }

    function onPointerMove(event) {
        if (gesture && event.pointerId === gesture.id) {
            gesture.x = event.clientX;
            gesture.y = event.clientY;
            const distance = Math.hypot(gesture.x - gesture.x0, gesture.y - gesture.y0);
            if (gesture.touch && distance > TOUCH_TOLERANCE_PX) cancelGesture();
            else if (!gesture.touch && distance > MOUSE_TOLERANCE_PX) begin();
            return;
        }
        if (drag && event.pointerId === drag.id) {
            drag.x = event.clientX;
            drag.y = event.clientY;
            if (Math.hypot(drag.x - drag.x0, drag.y - drag.y0) > TOUCH_TOLERANCE_PX) drag.moved = true;
            update();
            autoScroll();
        }
    }

    function onPointerUp(event) {
        if (gesture && event.pointerId === gesture.id) {
            cancelGesture();
            return;
        }
        if (drag && event.pointerId === drag.id) finish();
    }

    function onPointerCancel(event) {
        if (gesture && event.pointerId === gesture.id) cancelGesture();
        if (drag && event.pointerId === drag.id) abort();
    }

    function cancelGesture() {
        clearTimeout(gesture?.timer);
        gesture = null;
    }

    function begin() {
        const g = gesture;
        cancelGesture();
        const { root, item } = g;
        const time = grid().time;
        const isChore = item.dataset.gridItem === "chore";
        const lane = item.closest("[data-lane]");
        const origin = { allDay: false, start: time.parseLocal(item.dataset.start), end: time.parseLocal(item.dataset.end) };
        const fromMember = memberOf(item);
        drag = {
            mode: isChore && !g.handle ? "chore-move" : g.handle ? "resize-" + g.handle.dataset.handle : "move",
            root, item, id: g.id, touch: g.touch, x0: g.x0, y0: g.y0, x: g.x, y: g.y, moved: false,
            slot: slotMinutes(root), origin, fromMember, toMember: fromMember, lane,
            recurring: item.dataset.recurring === "true",
            grab: time.absolute(lane.dataset.date, minuteAt(item.closest("[data-lane-track]"), g.x0)) - origin.start
        };
        item.classList.add("is-lifted");
        root.classList.add("is-lanes-dragging");
        try { root.setPointerCapture?.(g.id); } catch (e) { /* capture is optional */ }
        helpers().haptic(10);
        update();
    }

    /** Computes the snapped result of the drag (times and lane) and draws the ghost. */
    function update() {
        const d = drag;
        if (!d) return;
        const { absolute, snap, dateOf } = grid().time;
        const duration = d.origin.end - d.origin.start;
        if (d.mode === "move" || d.mode === "chore-move") {
            const track = trackAt(d.root, d.y);
            d.lane = track.closest("[data-lane]");
            d.toMember = memberOf(d.lane);
            const pointer = absolute(d.lane.dataset.date, minuteAt(track, d.x));
            let start = snap(pointer - d.grab, d.slot);
            if (d.mode === "chore-move") {
                // a chore stays within one day
                const dayStart = absolute(dateOf(start + Math.min(duration, d.slot)), 0);
                start = Math.max(dayStart, Math.min(dayStart + DAY_MINUTES - duration, start));
            }
            d.result = { allDay: false, start, end: start + duration };
        } else {
            const edge = absolute(d.lane.dataset.date, snap(minuteAt(d.item.closest("[data-lane-track]"), d.x), d.slot));
            d.result = d.mode === "resize-end"
                ? { allDay: false, start: d.origin.start, end: Math.max(edge, d.origin.start + d.slot) }
                : { allDay: false, start: Math.min(edge, d.origin.end - d.slot), end: d.origin.end };
        }
        draw();
    }

    function draw() {
        const d = drag;
        d.ghost?.remove();
        const dayStart = grid().time.absolute(d.lane.dataset.date, 0);
        const from = Math.max(d.result.start, dayStart);
        const to = Math.min(d.result.end, dayStart + DAY_MINUTES);
        if (to > from) {
            d.ghost = d.item.cloneNode(true);
            d.ghost.removeAttribute("href");
            d.ghost.removeAttribute("data-lane-entry");
            d.ghost.classList.remove("is-lifted", "is-selected");
            d.ghost.classList.add("is-ghost");
            d.ghost.style.top = LANE_PADDING_PX + "px";
            d.ghost.style.setProperty("--start", String(from - dayStart));
            d.ghost.style.setProperty("--dur", String(to - from));
            d.ghost.querySelectorAll("[data-handle]").forEach(el => el.remove());
            const time = d.ghost.querySelector(".occ-time");
            if (time) time.textContent = timeRange(d.result);
            d.lane.querySelector("[data-lane-track]").append(d.ghost);
        }
        let label = grid().describe(d.result);
        if (d.toMember !== d.fromMember) label += " · " + nameOf(d.root, d.fromMember) + " → " + nameOf(d.root, d.toMember);
        showLabel(label);
    }

    function timeRange(result) {
        const { clock, minuteOf } = grid().time;
        return clock(minuteOf(result.start)) + "–" + clock(minuteOf(result.end));
    }

    const nameOf = (root, member) =>
        root.querySelector(`[data-lane][data-member="${member}"] .lanes-name`)?.textContent
        ?? root.querySelector(".lanes-row.is-all .lanes-name")?.textContent ?? "Alle";

    function showLabel(text) {
        const d = drag;
        if (!d.label) {
            d.label = document.createElement("div");
            d.label.className = "tgrid-drag-label";
            d.label.setAttribute("aria-live", "polite");
            document.body.append(d.label);
        }
        d.label.textContent = text;
        d.label.style.left = Math.max(8, Math.min(window.innerWidth - d.label.offsetWidth - 8, d.x - d.label.offsetWidth / 2)) + "px";
        d.label.style.top = Math.max(8, d.y - (d.touch ? DRAG_LABEL_OFFSET_TOUCH_PX : DRAG_LABEL_OFFSET_PX)) + "px";
    }

    /** Scrolls sideways while the pointer rests near an edge of the lanes (the names stay in place). */
    function autoScroll() {
        const d = drag;
        if (d.scrolling) return;
        const scroller = scrollerOf(d.root);
        const speed = () => {
            const box = scroller.getBoundingClientRect();
            const labelWidth = d.root.querySelector(".lanes-label").offsetWidth;
            return d.x < box.left + labelWidth + SCROLL_EDGE_PX && scroller.scrollLeft > 0 ? -SCROLL_STEP_PX
                : d.x > box.right - SCROLL_EDGE_PX && scroller.scrollLeft + scroller.clientWidth < scroller.scrollWidth - 1
                    ? SCROLL_STEP_PX : 0;
        };
        if (!speed()) return;
        d.scrolling = true;
        const step = () => {
            const amount = drag === d ? speed() : 0;
            if (!amount) {
                d.scrolling = false;
                return;
            }
            scroller.scrollLeft += amount;
            update();
            requestAnimationFrame(step);
        };
        requestAnimationFrame(step);
    }

    function cleanup(d) {
        d.ghost?.remove();
        d.label?.remove();
        d.item.classList.remove("is-lifted");
        d.root.classList.remove("is-lanes-dragging");
        lastDragEnd = Date.now();
        helpers().markDragged();
    }

    function abort() {
        const d = drag;
        drag = null;
        cleanup(d);
    }

    function finish() {
        const d = drag;
        drag = null;
        cleanup(d);
        if (!d.moved) {
            // lifted without moving: show the handles for start and end
            if (d.touch) d.item.classList.add("is-selected");
            return;
        }
        if (grid().sameTimes(d.origin, d.result) && d.fromMember === d.toMember) return;
        grid().commit(d);
    }

    function scrollToStart(root) {
        const scroller = scrollerOf(root);
        const track = root.querySelector("[data-lane-track]");
        const hour = track.offsetWidth / 24;
        const nowMinute = root.dataset.today === "true" ? +root.dataset.nowMinute : null;
        const firstHour = nowMinute === null ? FIRST_HOUR_SHOWN : Math.max(0, nowMinute / 60 - 1);
        scroller.scrollLeft = firstHour * hour;
    }

    function setUp() {
        const root = document.querySelector("[data-lanes]");
        if (!root || root.dataset.lanesReady) return;
        root.dataset.lanesReady = "true";
        applySlot(root, readSlot(root));
        window.NestwerkGrid.bindDetails(root);
        layout(root);
        if (keptScrollLeft === null) scrollToStart(root);
        else scrollerOf(root).scrollLeft = keptScrollLeft;
        // zoom changes the hour width; fonts change the height of titles
        new MutationObserver(() => layout(root)).observe(root, { attributes: true, attributeFilter: ["data-zoom"] });
        document.fonts?.ready.then(() => layout(root));
    }

    function moveNowLine() {
        const now = new Date();
        document.querySelectorAll(".lanes-now").forEach(line =>
            line.style.setProperty("--start", String(now.getHours() * 60 + now.getMinutes())));
    }

    document.addEventListener("DOMContentLoaded", () => {
        document.addEventListener("click", onClick, true);
        document.addEventListener("change", event => {
            const select = event.target.closest?.("[data-slot-select]");
            const root = select?.closest("[data-lanes]");
            if (!root) return;
            try { localStorage.setItem(SLOT_KEY, select.value); } catch (e) { /* only for this page */ }
            applySlot(root, +select.value);
        });
        document.addEventListener("pointerdown", onPointerDown);
        document.addEventListener("pointermove", onPointerMove);
        document.addEventListener("pointerup", onPointerUp);
        document.addEventListener("pointercancel", onPointerCancel);
        // while dragging with a finger the page must not scroll; before, it scrolls as usual
        document.addEventListener("touchmove", event => {
            if (drag?.touch) event.preventDefault();
        }, { passive: false });
        // entries are links: the browser's own link dragging and context menu would cancel the pointer events
        document.addEventListener("dragstart", event => {
            if (event.target.closest?.("[data-lanes] .lanes-track")) event.preventDefault();
        });
        document.addEventListener("contextmenu", event => {
            if (event.target.closest("[data-lanes] .lanes-track")) event.preventDefault();
        });
        document.addEventListener("keydown", event => {
            if (event.key === "Escape" && drag) abort();
        });
        // reloading after a change keeps the visible hours
        document.addEventListener("htmx:beforeRequest", () => {
            const root = document.querySelector("[data-lanes]");
            if (root) keptScrollLeft = scrollerOf(root).scrollLeft;
        });
        document.addEventListener("htmx:afterSettle", setUp);
        window.addEventListener("resize", () => {
            const root = document.querySelector("[data-lanes]");
            if (root) layout(root);
        });
        setInterval(moveNowLine, 60000);
        setUp();
    });
})();
