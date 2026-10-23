/* Nestwerk – time grid of the calendar: snapping slots, zoom, person columns and touch/mouse gestures to create,
   move, resize and hand over entries. */
(() => {
    "use strict";

    const SLOT_KEY = "nestwerk-calendar-slot";
    const ZOOM_KEY = "nestwerk-calendar-zoom";
    const SPLIT_COOKIE = "nestwerk-calendar-split";
    const ZOOM_LEVELS = [0.75, 1, 1.5, 2, 3];
    const LONG_PRESS_MS = 300;
    const TOUCH_TOLERANCE_PX = 8;
    const MOUSE_TOLERANCE_PX = 4;
    const EDGE_PX = 28;
    const EDGE_DELAY_MS = 700;
    const SCROLL_EDGE_PX = 48;
    const SCROLL_STEP_PX = 12;
    const DEFAULT_MINUTES = 60;
    const DAY_MINUTES = 1440;
    const CLICK_GUARD_MS = 350;
    const FIRST_HOUR_SHOWN = 7;
    const YEAR_SECONDS = 31536000;
    const PHONE = "(max-width: 40rem)";

    const helpers = () => window.Nestwerk;
    const calendar = () => window.NestwerkCalendar;

    /* --- Local dates ("YYYY-MM-DD") and points in time as day number × 1440 + minute ------------------------------ */

    const dayNumber = iso => Date.UTC(+iso.slice(0, 4), +iso.slice(5, 7) - 1, +iso.slice(8, 10)) / 86400000;
    const isoOf = day => new Date(day * 86400000).toISOString().slice(0, 10);
    const pad = n => String(n).padStart(2, "0");
    const clock = minutes => pad(Math.floor(minutes / 60)) + ":" + pad(minutes % 60);
    const absolute = (iso, minutes) => dayNumber(iso) * DAY_MINUTES + minutes;
    const parseLocal = value => absolute(value.slice(0, 10), +value.slice(11, 13) * 60 + +value.slice(14, 16));
    const dateOf = abs => isoOf(Math.floor(abs / DAY_MINUTES));
    const minuteOf = abs => ((abs % DAY_MINUTES) + DAY_MINUTES) % DAY_MINUTES;
    const localValue = abs => dateOf(abs) + "T" + clock(minuteOf(abs));
    const addDays = (iso, days) => isoOf(dayNumber(iso) + days);
    const dayLabel = iso => new Date(iso + "T12:00:00").toLocaleDateString("de-DE",
        { weekday: "short", day: "numeric", month: "numeric" });
    const snap = (minutes, slot) => Math.round(minutes / slot) * slot;
    const snapDown = (minutes, slot) => Math.floor(minutes / slot) * slot;

    /* --- Snapping: the chosen slot length in minutes; only how times snap, not the size ------------------------- */

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

    function switchSlot(root, minutes) {
        try { localStorage.setItem(SLOT_KEY, String(minutes)); } catch (e) { /* only for this page */ }
        applySlot(root, minutes);
    }

    /* --- Geometry ------------------------------------------------------------------------------------------------ */

    const columns = root => [...root.querySelectorAll(".tgrid-col")];
    const cells = root => [...root.querySelectorAll(".tgrid-allday-cell")];
    const scrollerOf = root => root.querySelector("[data-grid-scroll]");
    const memberOf = element => element?.dataset.member ?? "";

    function nearest(elements, x) {
        return elements.find(el => {
            const box = el.getBoundingClientRect();
            return x >= box.left && x < box.right;
        }) || (x < elements[0].getBoundingClientRect().left ? elements[0] : elements[elements.length - 1]);
    }

    function minuteAt(column, y) {
        const box = column.getBoundingClientRect();
        return Math.max(0, Math.min(DAY_MINUTES, (y - box.top) / box.height * DAY_MINUTES));
    }

    function isOverAllDayRow(root, y) {
        const box = root.querySelector("[data-allday-row]").getBoundingClientRect();
        return y >= box.top && y <= box.bottom;
    }

    // the day in lanes has no columns; its root carries the one shown day
    const visibleDates = root => columns(root).length
        ? [...new Set(columns(root).map(c => c.dataset.date))] : [root.dataset.date];

    /** The column of a day and person; falls back to the first or last day for days outside the view. */
    function columnFor(root, date, member) {
        const all = columns(root);
        const found = all.find(c => c.dataset.date === date && memberOf(c) === member);
        if (found) return found;
        const dates = visibleDates(root);
        const day = date < dates[0] ? dates[0] : dates.at(-1);
        return all.find(c => c.dataset.date === day && memberOf(c) === member) || all[0];
    }

    /* --- Scroll position as the time at the top of the visible hours ---------------------------------------------- */

    const stickyHeight = root => root.querySelector("[data-grid-top]")?.offsetHeight ?? 0;

    function bodyOffset(root) {
        const scroller = scrollerOf(root);
        return root.querySelector(".tgrid-body").getBoundingClientRect().top - scroller.getBoundingClientRect().top
            + scroller.scrollTop;
    }

    function topMinute(root) {
        const scroller = scrollerOf(root);
        if (!scroller) return null;
        const body = root.querySelector(".tgrid-body");
        return (scroller.scrollTop + stickyHeight(root) - bodyOffset(root)) / body.offsetHeight * DAY_MINUTES;
    }

    function scrollToMinute(root, minute) {
        const body = root.querySelector(".tgrid-body");
        scrollerOf(root).scrollTop = bodyOffset(root) + minute / DAY_MINUTES * body.offsetHeight - stickyHeight(root);
    }

    /** Kept across reloads, paging and switching the view, so the visible hours stay the same. */
    let keptMinute = null;

    function keepScroll() {
        const root = document.querySelector("[data-time-grid]");
        const minute = root ? topMinute(root) : null;
        if (minute !== null) keptMinute = minute;
    }

    /* --- Zoom: more space per hour, wider columns and larger text ------------------------------------------------- */

    function readZoom() {
        try {
            const stored = +localStorage.getItem(ZOOM_KEY);
            return ZOOM_LEVELS.includes(stored) ? stored : 1;
        } catch (e) {
            return 1;
        }
    }

    /** Sets the zoom and keeps the time and column under {@code anchor} (a point on screen) where they were. */
    function applyZoom(area, zoom, anchor) {
        const grid = area.matches("[data-time-grid]") ? area : null;
        const scroller = grid && scrollerOf(grid);
        let before = null;
        if (scroller && anchor) {
            const box = scroller.getBoundingClientRect();
            before = {
                y: (anchor.y - box.top + scroller.scrollTop) / scroller.scrollHeight,
                x: (anchor.x - box.left + scroller.scrollLeft) / scroller.scrollWidth,
                dy: anchor.y - box.top, dx: anchor.x - box.left
            };
        }
        area.style.setProperty("--zoom", String(zoom));
        area.dataset.zoom = String(zoom);
        area.querySelectorAll("[data-zoom-label]").forEach(label => {
            label.textContent = Math.round(zoom * 100) + " %";
        });
        area.querySelectorAll("[data-zoom-step]").forEach(button => {
            const step = +button.dataset.zoomStep;
            button.disabled = step < 0 ? zoom <= ZOOM_LEVELS[0] : zoom >= ZOOM_LEVELS.at(-1);
        });
        if (before) {
            scroller.scrollTop = before.y * scroller.scrollHeight - before.dy;
            scroller.scrollLeft = before.x * scroller.scrollWidth - before.dx;
        }
        if (grid) markScrollableX(grid);
    }

    function storeZoom(zoom) {
        try { localStorage.setItem(ZOOM_KEY, String(zoom)); } catch (e) { /* only for this page */ }
    }

    function stepZoom(area, step, anchor) {
        const current = +(area.dataset.zoom || 1);
        const index = ZOOM_LEVELS.findIndex(level => level >= current - 0.001);
        const next = ZOOM_LEVELS[Math.max(0, Math.min(ZOOM_LEVELS.length - 1,
            (index < 0 ? ZOOM_LEVELS.length - 1 : index) + step))];
        if (next === current) return;
        applyZoom(area, next, anchor || centerOf(area));
        storeZoom(next);
    }

    function centerOf(area) {
        const box = (scrollerOf(area) || area).getBoundingClientRect();
        return { x: box.left + box.width / 2, y: box.top + box.height / 2 };
    }

    /** Swiping pages the calendar only while the grid cannot be scrolled sideways itself. */
    function markScrollableX(root) {
        const scroller = scrollerOf(root);
        scroller?.classList.toggle("can-scroll-x", scroller.scrollWidth > scroller.clientWidth + 1);
    }

    /** Two fingers on the grid or the month: zoom smoothly and snap to the nearest level when letting go. */
    const pinch = { pointers: new Map(), start: 0, zoom: 1, area: null };

    function pinchDistance() {
        const [a, b] = [...pinch.pointers.values()];
        return Math.hypot(a.x - b.x, a.y - b.y);
    }

    function pinchCenter() {
        const [a, b] = [...pinch.pointers.values()];
        return { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 };
    }

    function onPinchDown(event) {
        if (event.pointerType !== "touch") return false;
        const area = event.target.closest("[data-zoomable]");
        if (!area || event.target.closest("dialog, .cal-toolbar")) return false;
        pinch.pointers.set(event.pointerId, { x: event.clientX, y: event.clientY });
        if (pinch.pointers.size !== 2) return false;
        cancelGesture();
        if (drag) abort();
        pinch.area = area;
        pinch.start = pinchDistance();
        pinch.zoom = +(area.dataset.zoom || 1);
        return true;
    }

    function onPinchMove(event) {
        if (!pinch.pointers.has(event.pointerId)) return false;
        pinch.pointers.set(event.pointerId, { x: event.clientX, y: event.clientY });
        if (pinch.pointers.size !== 2 || !pinch.area || !pinch.start) return false;
        const zoom = Math.max(ZOOM_LEVELS[0], Math.min(ZOOM_LEVELS.at(-1), pinch.zoom * pinchDistance() / pinch.start));
        applyZoom(pinch.area, Math.round(zoom * 100) / 100, pinchCenter());
        return true;
    }

    function onPinchEnd(event) {
        if (!pinch.pointers.delete(event.pointerId)) return false;
        if (!pinch.area || pinch.pointers.size > 0) return pinch.area !== null;
        const current = +(pinch.area.dataset.zoom || 1);
        const level = ZOOM_LEVELS.reduce((best, l) => Math.abs(l - current) < Math.abs(best - current) ? l : best);
        applyZoom(pinch.area, level, centerOf(pinch.area));
        storeZoom(level);
        helpers().markDragged();
        pinch.area = null;
        pinch.start = 0;
        return true;
    }

    function onWheel(event) {
        if (!event.ctrlKey) return;
        const area = event.target.closest("[data-zoomable]");
        if (!area || event.target.closest("dialog")) return;
        event.preventDefault();
        stepZoom(area, event.deltaY < 0 ? 1 : -1, { x: event.clientX, y: event.clientY });
    }

    /* --- One column per person ----------------------------------------------------------------------------------- */

    function toggleSplit(root) {
        const next = root.dataset.split === "person" ? "none" : "person";
        document.cookie = `${SPLIT_COOKIE}=${next}; path=/; max-age=${YEAR_SECONDS}; SameSite=Lax`;
        keepScroll();
        helpers().reloadCalendar();
    }

    const personName = (root, member) => member
        ? root.querySelector(`.tgrid-col[data-member="${member}"]`)?.dataset.memberName || "?"
        : "Alle";

    /** Members of the dragged entry after dropping it into another person's column. */
    function membersAfter(d) {
        if (d.fromMember === d.toMember) return d.members;
        const result = d.members.filter(m => m !== d.fromMember);
        if (d.toMember && !result.includes(d.toMember)) result.push(d.toMember);
        return result;
    }

    /* --- Reloading ----------------------------------------------------------------------------------------------- */

    /** Reloads the shown period; the calendar never jumps to another period after a change. */
    function reload() {
        keepScroll();
        helpers().reloadCalendar();
    }

    /** "Jetzt am Do., 29.10." when the changed entry left the shown period. */
    function outsideNote(root, date) {
        return date && !visibleDates(root).includes(date) ? " Jetzt am " + dayLabel(date) + "." : "";
    }

    /* --- Dialogs: quick creation, choice for series -------------------------------------------------------------- */

    function openQuick(root, range, member) {
        const dialog = root.querySelector("#grid-quick");
        if (!dialog) return;
        const allDay = Boolean(range.allDay);
        const context = allDay
            ? { allDay, startDate: range.first, endDate: range.last, member: member || "" }
            : {
                allDay, startDate: dateOf(range.start), startTime: clock(minuteOf(range.start)),
                endDate: dateOf(range.end), endTime: clock(minuteOf(range.end)), member: member || ""
            };
        // tapping a free time is most often a new event; the dialog offers the other types as tabs
        window.NestwerkCreate.open(dialog, context, "event");
    }

    /** The link "Mehr Details" takes what was entered so far to the full form and comes back to this view. */
    function detailsUrl(form) {
        const params = new URLSearchParams({ date: form.elements.startDate.value });
        if (form.elements.allDay.value !== "true") {
            params.set("start", form.elements.startTime.value);
            params.set("end", form.elements.endTime.value);
        }
        params.set("back", window.location.pathname + window.location.search);
        return "/calendar/events/new?" + params;
    }

    function askScope(root, seriesAllowed, personChanges) {
        const dialog = root.querySelector("#grid-series");
        dialog.querySelector('[data-series-choice="SERIES"]').disabled = !seriesAllowed;
        dialog.querySelector("[data-series-hint]").hidden = seriesAllowed;
        dialog.querySelector("[data-series-person]").hidden = !personChanges;
        return new Promise(resolve => {
            let answer = null;
            const choose = event => {
                const button = event.target.closest("[data-series-choice]");
                if (!button || button.disabled) return;
                answer = button.dataset.seriesChoice;
                dialog.close();
            };
            dialog.addEventListener("click", choose);
            dialog.addEventListener("close", () => {
                dialog.removeEventListener("click", choose);
                resolve(answer);
            }, { once: true });
            dialog.showModal();
        });
    }

    /* --- Gestures ------------------------------------------------------------------------------------------------ */

    let gesture = null;
    let drag = null;
    let lastDragEnd = 0;

    function deselect(root) {
        root?.querySelectorAll(".tgrid-item.is-selected").forEach(el => el.classList.remove("is-selected"));
    }

    function onPointerDown(event) {
        if (onPinchDown(event) || pinch.area) return;
        const root = event.target.closest("[data-time-grid]");
        if (!root || drag || event.button > 0 || !event.isPrimary
            || event.target.closest("dialog, .cal-toolbar, .cal-legend-row, .tgrid-head, .tgrid-people, .tgrid-hint")) return;
        const item = event.target.closest("[data-grid-item]");
        const handle = event.target.closest("[data-handle]");
        const column = event.target.closest(".tgrid-col");
        const cell = event.target.closest(".tgrid-allday-cell");
        if (!item || !item.classList.contains("is-selected")) deselect(root);
        if (item ? item.dataset.editable !== "true" : (!column && !cell) || root.dataset.canCreate !== "true") return;
        gesture = {
            root, item, handle, column, cell, id: event.pointerId, touch: event.pointerType !== "mouse",
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
        if (onPinchMove(event)) return;
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
        if (onPinchEnd(event)) return;
        if (gesture && event.pointerId === gesture.id) {
            cancelGesture();
            return;
        }
        if (drag && event.pointerId === drag.id) finish();
    }

    function onPointerCancel(event) {
        onPinchEnd(event);
        if (gesture && event.pointerId === gesture.id) cancelGesture();
        if (drag && event.pointerId === drag.id) abort();
    }

    function cancelGesture() {
        clearTimeout(gesture?.timer);
        gesture = null;
    }

    /** Turns the pressed pointer into a drag: lifts the item (or starts a new one) and shows the ghost. */
    function begin() {
        const g = gesture;
        cancelGesture();
        const { root, item } = g;
        const type = item?.dataset.gridItem;
        const mode = !item ? (g.cell ? "allday-create" : "create")
            : type === "allday" ? (g.handle ? "allday-resize-" + g.handle.dataset.handle : "allday-move")
                : type === "todo" ? "todo-move"
                    : type === "chore" && !g.handle ? "chore-move"
                        : g.handle ? "resize-" + g.handle.dataset.handle : "move";
        const fromMember = item ? memberOf(item) : memberOf(g.column || g.cell);
        drag = {
            mode, root, item, id: g.id, touch: g.touch, x0: g.x0, y0: g.y0, x: g.x, y: g.y, moved: false,
            slot: slotMinutes(root), offset: 0, members: (item?.dataset.members || "").split(",").filter(Boolean),
            recurring: item?.dataset.recurring === "true", fromMember, toMember: fromMember
        };
        if (item && !["allday", "todo"].includes(type)) {
            drag.origin = { allDay: false, start: parseLocal(item.dataset.start), end: parseLocal(item.dataset.end) };
            const column = g.column || nearest(columns(root), g.x0);
            drag.grab = absolute(column.dataset.date, minuteAt(column, g.y0)) - drag.origin.start;
        } else if (item) {
            drag.origin = { allDay: true, first: item.dataset.first, last: item.dataset.last };
            drag.grabDate = nearest(cells(root), g.x0).dataset.date;
        } else if (g.cell) {
            drag.anchorDate = g.cell.dataset.date;
        } else {
            drag.column = g.column;
            drag.anchor = absolute(g.column.dataset.date, snap(minuteAt(g.column, g.y0), drag.slot));
        }
        item?.classList.add("is-lifted");
        root.classList.add("is-grid-dragging");
        root.querySelectorAll(".tgrid-zone").forEach(zone =>
            zone.classList.toggle("is-relevant", drag.members.includes(zone.dataset.member)));
        markOverlapZones(root, item);
        try { root.setPointerCapture?.(g.id); } catch (e) { /* capture is optional */ }
        helpers().haptic(10);
        update();
    }

    /**
     * Times of other events and chores a dragged entry must not be put over: a chore must not lie over a flagged
     * entry or, when flagged itself, over any entry; an event only matters for chores. Candidates count whoever
     * the entry is given to; they are only drawn in the columns of the entry's persons.
     */
    function markOverlapZones(root, item) {
        const kind = item?.dataset.gridItem;
        const own = item?.dataset.overlapKey;
        const flagged = item?.dataset.noOverlap === "true";
        root.querySelectorAll(".tgrid-zone[data-overlap-key]").forEach(zone => {
            const candidate = Boolean(own) && zone.dataset.overlapKey !== own
                && (kind === "chore" || zone.dataset.overlapKind === "chore")
                && (flagged || zone.dataset.overlapFlag === "true");
            zone.classList.toggle("is-overlap-candidate", candidate);
            zone.classList.toggle("is-relevant", candidate && drag.members.includes(zone.dataset.member));
        });
    }

    /** Computes the snapped result of the drag (times and person column) and draws the ghost. */
    function update() {
        const d = drag;
        if (!d) return;
        const root = d.root;
        const overAllDay = isOverAllDayRow(root, d.y);
        trackEdge();
        switch (d.mode) {
            case "create": {
                const current = absolute(d.column.dataset.date, snap(minuteAt(d.column, d.y), d.slot));
                const start = Math.min(d.anchor, current);
                const end = Math.max(d.anchor, current);
                d.result = { allDay: false, start, end: end - start < d.slot ? start + d.slot : end };
                break;
            }
            case "allday-create": {
                const date = nearest(cells(root), d.x).dataset.date;
                const [first, last] = [d.anchorDate, date].sort();
                d.result = { allDay: true, first, last };
                break;
            }
            case "move":
            case "chore-move": {
                if (overAllDay && d.mode === "move" && !d.recurring) {
                    const cell = nearest(cells(root), d.x);
                    const date = addDays(cell.dataset.date, d.offset);
                    d.toMember = memberOf(cell);
                    d.result = { allDay: true, first: date, last: date };
                    break;
                }
                const column = nearest(columns(root), d.x);
                d.toMember = memberOf(column);
                const pointer = absolute(column.dataset.date, minuteAt(column, d.y));
                const duration = d.origin.end - d.origin.start;
                let start = snap(pointer - d.grab, d.slot) + d.offset * DAY_MINUTES;
                if (d.mode === "chore-move") {
                    // a chore stays within one day
                    const dayStart = absolute(dateOf(start + Math.min(duration, d.slot)), 0);
                    start = Math.max(dayStart, Math.min(dayStart + DAY_MINUTES - duration, start));
                }
                d.result = { allDay: false, start, end: start + duration };
                break;
            }
            case "resize-start":
            case "resize-end": {
                const column = columnFor(root, nearest(columns(root), d.x).dataset.date, d.fromMember);
                const edge = absolute(column.dataset.date, snap(minuteAt(column, d.y), d.slot));
                d.result = d.mode === "resize-end"
                    ? { allDay: false, start: d.origin.start, end: Math.max(edge, d.origin.start + d.slot) }
                    : { allDay: false, start: Math.min(edge, d.origin.end - d.slot), end: d.origin.end };
                break;
            }
            case "allday-move": {
                if (!overAllDay && !d.recurring && d.y > root.querySelector("[data-allday-row]").getBoundingClientRect().bottom) {
                    const column = nearest(columns(root), d.x);
                    d.toMember = memberOf(column);
                    const start = absolute(column.dataset.date, snap(minuteAt(column, d.y), d.slot)) + d.offset * DAY_MINUTES;
                    d.result = { allDay: false, start, end: start + DEFAULT_MINUTES };
                    break;
                }
                const cell = nearest(cells(root), d.x);
                d.toMember = memberOf(cell);
                const shift = dayNumber(cell.dataset.date) - dayNumber(d.grabDate) + d.offset;
                d.result = { allDay: true, first: addDays(d.origin.first, shift), last: addDays(d.origin.last, shift) };
                break;
            }
            case "todo-move": {
                const cell = nearest(cells(root), d.x);
                d.toMember = memberOf(cell);
                const date = addDays(cell.dataset.date, d.offset);
                d.result = { allDay: true, first: date, last: date };
                break;
            }
            case "allday-resize-start": {
                const date = nearest(cells(root), d.x).dataset.date;
                d.result = { allDay: true, first: date > d.origin.last ? d.origin.last : date, last: d.origin.last };
                break;
            }
            case "allday-resize-end": {
                const date = nearest(cells(root), d.x).dataset.date;
                d.result = { allDay: true, first: d.origin.first, last: date < d.origin.first ? d.origin.first : date };
                break;
            }
        }
        draw();
    }

    function draw() {
        const d = drag;
        const root = d.root;
        d.ghost?.remove();
        const blocked = blockingZone(root, membersAfter(d), d.result);
        let label = describe(d.result);
        if (d.toMember !== d.fromMember) {
            label += " · " + personName(root, d.fromMember) + " → " + personName(root, d.toMember);
        }
        if (d.result.allDay) {
            drawAllDayGhost(d);
        } else {
            const date = dateOf(d.result.start);
            const column = columnFor(root, date, d.toMember);
            const startMinute = date === column.dataset.date ? minuteOf(d.result.start) : 0;
            d.ghost = document.createElement("div");
            d.ghost.className = "occ tgrid-item tgrid-event is-ghost" + (d.item ? "" : " is-draft");
            d.ghost.style.setProperty("--start", String(startMinute));
            d.ghost.style.setProperty("--dur", String(Math.min(d.result.end - d.result.start, DAY_MINUTES - startMinute)));
            d.ghost.style.setProperty("--col", "0");
            d.ghost.style.setProperty("--cols", "1");
            const color = d.item?.style.getPropertyValue("--occ-color");
            if (color) d.ghost.style.setProperty("--occ-color", color);
            d.ghost.innerHTML = '<span class="occ-time"></span><span class="occ-title"><span></span></span>';
            d.ghost.querySelector(".occ-time").textContent = clock(minuteOf(d.result.start)) + "–" + clock(minuteOf(d.result.end));
            d.ghost.querySelector(".occ-title span").textContent = d.item?.dataset.title || "Neuer Termin";
            column.append(d.ghost);
        }
        d.ghost?.querySelectorAll(".tgrid-item").forEach(el => el.classList.toggle("is-blocked", Boolean(blocked)));
        d.ghost?.classList.toggle("is-blocked", Boolean(blocked));
        root.querySelectorAll(".tgrid-zone.is-hit").forEach(zone => zone.classList.toggle("is-hit", zone === blocked));
        blocked?.classList.add("is-hit");
        showLabel(blocked ? label + " · gesperrt: " + blocked.dataset.label : label, Boolean(blocked));
    }

    /** The ghost in the all-day row; split by person it covers the cells of that person only. */
    function drawAllDayGhost(d) {
        const root = d.root;
        const all = cells(root);
        const split = root.dataset.split === "person";
        const inRange = all.filter(c => c.dataset.date >= d.result.first && c.dataset.date <= d.result.last
            && (!split || memberOf(c) === d.toMember));
        if (!inRange.length) return;
        d.ghost = document.createElement("div");
        d.ghost.className = "tgrid-ghost-group";
        const pieces = split ? inRange.map(c => [c, c]) : [[inRange[0], inRange.at(-1)]];
        for (const [first, last] of pieces) {
            const piece = document.createElement("div");
            piece.className = "tgrid-lane-item tgrid-ghost-lane";
            piece.style.gridColumn = `${all.indexOf(first) + 2} / ${all.indexOf(last) + 3}`;
            piece.innerHTML = '<span class="occ occ-allday tgrid-item is-ghost"><span class="occ-title"></span></span>';
            piece.querySelector(".occ-title").textContent = d.item?.dataset.title || "Neuer Termin";
            d.ghost.append(piece);
        }
        root.querySelector("[data-allday-row]").append(d.ghost);
    }

    /** E.g. "Mi., 21.10. · 10:15–11:15" or "Mi., 21.10. – Fr., 23.10. · ganztägig". */
    function describe(result) {
        if (result.allDay) {
            return result.first === result.last ? dayLabel(result.first) + " · ganztägig"
                : dayLabel(result.first) + " – " + dayLabel(result.last) + " · ganztägig";
        }
        return dayLabel(dateOf(result.start)) + " · " + clock(minuteOf(result.start)) + "–" + clock(minuteOf(result.end));
    }

    function showLabel(text, blocked) {
        const d = drag;
        if (!d.label) {
            d.label = document.createElement("div");
            d.label.className = "tgrid-drag-label";
            d.label.setAttribute("aria-live", "polite");
            document.body.append(d.label);
        }
        d.label.textContent = text;
        d.label.classList.toggle("is-blocked", blocked);
        d.label.style.left = Math.max(8, Math.min(window.innerWidth - d.label.offsetWidth - 8, d.x - d.label.offsetWidth / 2)) + "px";
        d.label.style.top = Math.max(8, d.y - (d.touch ? 84 : 44)) + "px";
    }

    /** A blocked zone of a member of the dragged entry (after a change of person) that the result touches. */
    function blockingZone(root, members, result) {
        if (!members.length) return null;
        const start = result.allDay ? absolute(result.first, 0) : result.start;
        const end = result.allDay ? absolute(result.last, DAY_MINUTES) : result.end;
        for (const column of columns(root)) {
            const dayStart = absolute(column.dataset.date, 0);
            for (const zone of column.querySelectorAll('.tgrid-zone[data-strength="block"], .tgrid-zone.is-overlap-candidate')) {
                if (!members.includes(zone.dataset.member)) continue;
                const from = dayStart + +zone.dataset.startMinute;
                const to = dayStart + +zone.dataset.endMinute;
                if (from < end && to > start) return zone;
            }
        }
        return null;
    }

    /**
     * Holding the item at the left or right edge scrolls a zoomed grid sideways; once it cannot scroll further it
     * pages one day per moment into that direction.
     */
    function trackEdge() {
        const d = drag;
        if (!["move", "allday-move", "todo-move"].includes(d.mode)) return;
        const scroller = scrollerOf(d.root);
        const box = scroller.getBoundingClientRect();
        const direction = d.x < box.left + EDGE_PX ? -1 : d.x > box.right - EDGE_PX ? 1 : 0;
        const canScroll = direction < 0 ? scroller.scrollLeft > 0
            : direction > 0 && scroller.scrollLeft + scroller.clientWidth < scroller.scrollWidth - 1;
        const edge = canScroll ? 0 : direction;
        if (edge === d.edgeDirection) return;
        clearInterval(d.edgeTimer);
        d.edgeDirection = edge;
        if (!edge) return;
        d.edgeTimer = setInterval(() => {
            if (!drag) return;
            drag.offset += edge;
            helpers().haptic(6);
            update();
        }, EDGE_DELAY_MS);
    }

    /** Scrolls the grid while the pointer rests near an edge of the visible hours or of a zoomed grid. */
    function autoScroll() {
        const d = drag;
        if (d.scrolling) return;
        const scroller = scrollerOf(d.root);
        const speed = () => {
            const box = scroller.getBoundingClientRect();
            const top = box.top + stickyHeight(d.root);
            const vertical = d.y >= top && d.y < top + SCROLL_EDGE_PX ? -SCROLL_STEP_PX
                : d.y > box.bottom - SCROLL_EDGE_PX ? SCROLL_STEP_PX : 0;
            const horizontal = d.x < box.left + EDGE_PX * 2 && scroller.scrollLeft > 0 ? -SCROLL_STEP_PX
                : d.x > box.right - EDGE_PX * 2 && scroller.scrollLeft + scroller.clientWidth < scroller.scrollWidth - 1
                    ? SCROLL_STEP_PX : 0;
            return { vertical, horizontal };
        };
        const first = speed();
        if (!first.vertical && !first.horizontal) return;
        d.scrolling = true;
        const step = () => {
            const amount = drag === d ? speed() : { vertical: 0, horizontal: 0 };
            if (!amount.vertical && !amount.horizontal) {
                d.scrolling = false;
                return;
            }
            scroller.scrollTop += amount.vertical;
            scroller.scrollLeft += amount.horizontal;
            update();
            requestAnimationFrame(step);
        };
        requestAnimationFrame(step);
    }

    function cleanup(d) {
        clearInterval(d.edgeTimer);
        d.ghost?.remove();
        d.label?.remove();
        d.item?.classList.remove("is-lifted");
        d.root.classList.remove("is-grid-dragging");
        d.root.querySelectorAll(".tgrid-zone.is-relevant, .tgrid-zone.is-hit, .tgrid-zone.is-overlap-candidate")
            .forEach(zone => zone.classList.remove("is-relevant", "is-hit", "is-overlap-candidate"));
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
        const root = d.root;
        if (!d.moved && d.offset === 0) {
            if (d.mode === "create") {
                openQuick(root, { allDay: false, start: d.anchor, end: d.anchor + DEFAULT_MINUTES }, d.fromMember);
            } else if (d.mode === "allday-create") {
                openQuick(root, { allDay: true, first: d.anchorDate, last: d.anchorDate }, d.fromMember);
            } else if (d.touch && d.item) {
                // lifted without moving: show the handles for start and end
                d.item.classList.add("is-selected");
            }
            return;
        }
        if (d.mode === "create" || d.mode === "allday-create") {
            openQuick(root, d.result, d.toMember);
            return;
        }
        const blocked = blockingZone(root, membersAfter(d), d.result);
        if (blocked) {
            helpers().showToast(`Dort ist gesperrt (${blocked.dataset.label}). Der Eintrag bleibt, wo er war.`, "error");
            return;
        }
        if (sameTimes(d.origin, d.result) && d.fromMember === d.toMember) return;
        commit(d);
    }

    function sameTimes(a, b) {
        return a.allDay === b.allDay && (a.allDay ? a.first === b.first && a.last === b.last
            : a.start === b.start && a.end === b.end);
    }

    function timesParams(times) {
        return times.allDay
            ? { allDay: "true", startDate: times.first, endDate: times.last }
            : { allDay: "false", start: localValue(times.start), end: localValue(times.end) };
    }

    /** Person change for the server: who leaves and who takes over (empty: column "Alle"); nothing if unchanged. */
    function swapParams(from, to) {
        return from === to ? {} : { fromMember: from, toMember: to };
    }

    async function commit(d) {
        const root = d.root;
        const target = d.result.allDay ? d.result.first : dateOf(d.result.start);
        const personChanges = d.fromMember !== d.toMember;
        if (d.item?.dataset.gridItem === "chore") {
            const url = `/calendar/actions/chores/${d.item.dataset.choreId}/update`;
            const resized = d.result.end - d.result.start !== d.origin.end - d.origin.start;
            await run(root, url,
                { date: dateOf(d.result.start), start: clock(minuteOf(d.result.start)),
                    ...(resized ? { minutes: d.result.end - d.result.start } : {}),
                    ...(personChanges ? { member: d.toMember } : {}) },
                { date: dateOf(d.origin.start), start: clock(minuteOf(d.origin.start)),
                    ...(resized ? { minutes: d.origin.end - d.origin.start } : {}),
                    ...(personChanges ? { member: d.fromMember } : {}) }, target);
            return;
        }
        if (d.mode === "todo-move") {
            const url = `/calendar/actions/todos/${d.item.dataset.todoId}/move`;
            await run(root, url, { dueOn: d.result.first, from: d.fromMember, to: d.toMember },
                { dueOn: d.origin.first, from: d.toMember, to: d.fromMember }, target);
            return;
        }
        const timesChange = !sameTimes(d.origin, d.result);
        let scope = "SINGLE";
        if (d.recurring) {
            const sameDay = !d.result.allDay && !d.origin.allDay && dateOf(d.result.start) === dateOf(d.origin.start);
            scope = timesChange ? await askScope(root, sameDay, personChanges) : "OCCURRENCE";
            if (!scope) return;
        }
        const extra = d.recurring ? { scope, recurrenceId: d.item.dataset.recurrenceId } : { scope };
        const url = `/calendar/actions/events/${d.item.dataset.eventId}/reschedule`;
        const times = timesChange ? timesParams(d.result) : {};
        const undoTimes = timesChange ? timesParams(d.origin) : {};
        await run(root, url, { ...times, ...extra, ...swapParams(d.fromMember, d.toMember) },
            { ...undoTimes, ...extra, ...swapParams(d.toMember, d.fromMember) }, target);
    }

    async function run(root, url, params, undoParams, date) {
        try {
            const result = await calendar().post(url, params);
            calendar().report({ ...result, message: result.message + outsideNote(root, date) }, async () => {
                await calendar().post(url, undoParams);
                reload();
            });
        } catch (error) {
            helpers().showToast(error.message, "error");
        }
        reload();
    }

    /* --- Taps ---------------------------------------------------------------------------------------------------- */

    function onClick(event) {
        const zoomStep = event.target.closest("[data-zoomable] [data-zoom-step]");
        if (zoomStep) {
            stepZoom(zoomStep.closest("[data-zoomable]"), +zoomStep.dataset.zoomStep);
            return;
        }
        const root = event.target.closest("[data-time-grid]");
        if (!root || event.target.closest("dialog")) return;
        if (event.target.closest("[data-split-toggle]")) {
            toggleSplit(root);
            return;
        }
        if (!event.target.closest(".tgrid-allday, .tgrid-body")) return;
        if (Date.now() - lastDragEnd < CLICK_GUARD_MS) {
            event.preventDefault();
            event.stopPropagation();
            return;
        }
        const item = event.target.closest("[data-grid-item]");
        if (item) {
            event.preventDefault();
            calendar().openSheet(item.dataset.sheetUrl);
            return;
        }
        if (event.target.closest(".occ") || root.dataset.canCreate !== "true") return;
        const column = event.target.closest(".tgrid-col");
        if (column) {
            const start = absolute(column.dataset.date, snapDown(minuteAt(column, event.clientY), slotMinutes(root)));
            openQuick(root, { allDay: false, start, end: start + DEFAULT_MINUTES }, memberOf(column));
            return;
        }
        const cell = event.target.closest(".tgrid-allday-cell");
        if (cell) openQuick(root, { allDay: true, first: cell.dataset.date, last: cell.dataset.date }, memberOf(cell));
    }

    /* --- Set-up per rendered calendar ---------------------------------------------------------------------------- */

    function setUp() {
        const area = document.querySelector("[data-zoomable]");
        if (area && !area.dataset.zoomReady) {
            area.dataset.zoomReady = "true";
            applyZoom(area, readZoom(), null);
        }
        const root = document.querySelector("[data-time-grid]");
        if (!root || root.dataset.gridReady) return;
        root.dataset.gridReady = "true";
        if (root.dataset.view === "week" && matchMedia(PHONE).matches) {
            window.location.replace(`/calendar/days3?date=${root.dataset.focus}`);
            return;
        }
        applySlot(root, readSlot(root));
        root.querySelectorAll("[data-split-toggle]").forEach(button =>
            button.setAttribute("aria-pressed", String(root.dataset.split === "person")));
        const scroller = scrollerOf(root);
        root.style.setProperty("--grid-top", Math.round(scroller.getBoundingClientRect().top + window.scrollY) + "px");
        if (keptMinute !== null) {
            scrollToMinute(root, keptMinute);
        } else {
            const today = root.querySelector(".tgrid-col.is-today");
            scrollToMinute(root, today ? Math.max(0, +root.dataset.nowMinute - DEFAULT_MINUTES) : FIRST_HOUR_SHOWN * 60);
        }
        // in a grid wider than the screen the focus day comes into view
        const focus = root.querySelector(`.tgrid-col[data-date="${root.dataset.focus}"]`);
        if (focus && scroller.scrollWidth > scroller.clientWidth) {
            scroller.scrollLeft = focus.offsetLeft - root.querySelector(".tgrid-axis").offsetWidth;
        }
        markScrollableX(root);
        const details = root.querySelector("[data-quick-details]");
        details?.addEventListener("click", () => { details.href = detailsUrl(details.closest("form")); });
    }

    /** Shared with calendar-lanes.js, which shows the day in lanes instead of a time grid. */
    window.NestwerkGrid = Object.freeze({
        time: Object.freeze({ absolute, parseLocal, dateOf, minuteOf, clock, snap }),
        describe,
        sameTimes,
        /** Saves a finished drag from the lanes (same server calls, series choice and undo as in the grid). */
        commit,
        openQuickAt(root, date, startMinute, endMinute, member) {
            openQuick(root, { allDay: false, start: absolute(date, startMinute), end: absolute(date, endMinute) }, member);
        },
        bindDetails(root) {
            const details = root.querySelector("[data-quick-details]");
            details?.addEventListener("click", () => { details.href = detailsUrl(details.closest("form")); });
        }
    });

    function moveNowLine() {
        const now = new Date();
        document.querySelectorAll(".tgrid-now").forEach(line =>
            line.style.setProperty("--start", String(now.getHours() * 60 + now.getMinutes())));
    }

    document.addEventListener("DOMContentLoaded", () => {
        document.addEventListener("pointerdown", onPointerDown);
        document.addEventListener("pointermove", onPointerMove);
        document.addEventListener("pointerup", onPointerUp);
        document.addEventListener("pointercancel", onPointerCancel);
        document.addEventListener("click", onClick, true);
        document.addEventListener("change", event => {
            const select = event.target.closest?.("[data-slot-select]");
            const root = select?.closest("[data-time-grid]");
            if (root) switchSlot(root, +select.value);
        });
        document.addEventListener("wheel", onWheel, { passive: false });
        // while dragging or pinching with fingers the page must not scroll; before, it scrolls as usual
        document.addEventListener("touchmove", event => {
            if (drag?.touch || pinch.area) event.preventDefault();
        }, { passive: false });
        // entries are links: the browser's own link dragging would cancel the pointer events
        document.addEventListener("dragstart", event => {
            if (event.target.closest?.("[data-time-grid] .tgrid-allday, [data-time-grid] .tgrid-body")) event.preventDefault();
        });
        document.addEventListener("contextmenu", event => {
            if (event.target.closest("[data-time-grid] .tgrid-allday, [data-time-grid] .tgrid-body")) event.preventDefault();
        });
        document.addEventListener("keydown", event => {
            if (event.key === "Escape" && drag) abort();
        });
        // paging and switching the view keep the visible hours
        document.addEventListener("htmx:beforeRequest", keepScroll);
        document.addEventListener("htmx:afterSettle", setUp);
        window.addEventListener("resize", () => {
            const root = document.querySelector("[data-time-grid]");
            if (root) markScrollableX(root);
        });
        setInterval(moveNowLine, 60000);
        setUp();
    });
})();
