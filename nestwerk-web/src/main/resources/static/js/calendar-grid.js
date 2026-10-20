/* Nestwerk – time grid of the calendar: snapping slots and touch/mouse gestures to create, move and resize events. */
(() => {
    "use strict";

    const SLOT_KEY = "nestwerk-calendar-slot";
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
    const PHONE = "(max-width: 40rem)";

    const helpers = () => window.Nestwerk;

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

    /* --- Snapping and zoom: fine (5 min, more space per hour) or coarse (15 min) ---------------------------------- */

    function readSlot() {
        try {
            return localStorage.getItem(SLOT_KEY) === "fine" ? "fine" : "coarse";
        } catch (e) {
            return "coarse";
        }
    }

    function applySlot(root, choice) {
        root.dataset.slot = choice;
        root.querySelectorAll("[data-slot-choice]").forEach(button =>
            button.setAttribute("aria-pressed", String(button.dataset.slotChoice === choice)));
    }

    const slotMinutes = root => +(root.dataset.slot === "fine" ? root.dataset.slotFine : root.dataset.slotCoarse);

    function switchSlot(root, choice) {
        const scroller = root.querySelector("[data-grid-scroll]");
        const center = (scroller.scrollTop + scroller.clientHeight / 2) / scroller.scrollHeight;
        try { localStorage.setItem(SLOT_KEY, choice); } catch (e) { /* only for this page */ }
        applySlot(root, choice);
        scroller.scrollTop = center * scroller.scrollHeight - scroller.clientHeight / 2;
    }

    /* --- Geometry ------------------------------------------------------------------------------------------------ */

    const columns = root => [...root.querySelectorAll(".tgrid-col")];
    const cells = root => [...root.querySelectorAll(".tgrid-allday-cell")];

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

    const visibleDates = root => columns(root).map(c => c.dataset.date);

    /* --- Server calls -------------------------------------------------------------------------------------------- */

    async function post(url, params) {
        const token = document.querySelector('meta[name="_csrf"]')?.content;
        const header = document.querySelector('meta[name="_csrf_header"]')?.content;
        const headers = { "Accept": "application/json", "Content-Type": "application/x-www-form-urlencoded" };
        if (token && header) headers[header] = token;
        const response = await fetch(url, { method: "POST", headers, body: new URLSearchParams(params) });
        let body = {};
        try { body = await response.json(); } catch (e) { /* no JSON, e.g. login page */ }
        if (!response.ok) throw new Error(body.message || "Das hat leider nicht geklappt.");
        return body;
    }

    let pendingScroll = null;

    /** Reloads the grid; shows the period of {@code date} if that day is not visible. */
    function reload(root, date) {
        pendingScroll = root.querySelector("[data-grid-scroll]")?.scrollTop ?? null;
        if (date && !visibleDates(root).includes(date) && window.htmx) {
            const url = `/calendar/${root.dataset.view}?date=${date}`;
            window.htmx.ajax("GET", url, { target: "#calendar", select: "#calendar", swap: "outerHTML" })
                .then(() => history.replaceState(null, "", url));
            pendingScroll = null;
            return;
        }
        helpers().reloadCalendar();
    }

    /** A toast with "Rückgängig"; warnings of blockers are shown in the same toast. */
    function report(result, undo) {
        const warnings = result.warnings || [];
        const text = warnings.length ? result.message + " Achtung: " + warnings.join(" ") : result.message;
        const region = document.querySelector("[data-toasts]");
        if (!region || !undo) {
            helpers().showToast(text, warnings.length ? "error" : "success");
            return;
        }
        const toast = document.createElement("div");
        toast.className = "toast toast-action" + (warnings.length ? " toast-warn" : "");
        toast.setAttribute("role", warnings.length ? "alert" : "status");
        const message = document.createElement("span");
        message.textContent = text;
        const button = document.createElement("button");
        button.type = "button";
        button.className = "toast-undo";
        button.textContent = "Rückgängig";
        button.addEventListener("click", async () => {
            toast.remove();
            try {
                await undo();
                helpers().showToast("Rückgängig gemacht.", "success");
            } catch (error) {
                helpers().showToast(error.message, "error");
            }
        });
        toast.append(message, button);
        region.append(toast);
        if (warnings.length) helpers().haptic([12, 60, 12]);
        setTimeout(() => {
            if (!toast.isConnected || toast.classList.contains("is-dragging")) return;
            toast.classList.add("is-leaving");
            toast.addEventListener("animationend", () => toast.remove(), { once: true });
            setTimeout(() => toast.remove(), 400);
        }, warnings.length ? 9000 : 6000);
    }

    /* --- Dialogs: quick creation, bottom sheet, choice for series ------------------------------------------------ */

    function openQuick(root, range) {
        const dialog = root.querySelector("#grid-quick");
        if (!dialog) return;
        const form = dialog.querySelector("form");
        form.reset();
        const set = (name, value) => { form.elements[name].value = value; };
        const allDay = Boolean(range.allDay);
        set("allDay", String(allDay));
        form.classList.toggle("is-allday", allDay);
        form.querySelectorAll("[data-time-field]").forEach(input => {
            input.required = !allDay;
            input.disabled = allDay;
        });
        if (allDay) {
            set("startDate", range.first);
            set("endDate", range.last);
        } else {
            set("startDate", dateOf(range.start));
            set("startTime", clock(minuteOf(range.start)));
            set("endDate", dateOf(range.end));
            set("endTime", clock(minuteOf(range.end)));
        }
        dialog.showModal();
        form.elements.title.focus();
    }

    /** The link "Mehr Details" takes what was entered so far to the full form. */
    function detailsUrl(form) {
        const params = new URLSearchParams({ date: form.elements.startDate.value });
        if (form.elements.allDay.value !== "true") {
            params.set("start", form.elements.startTime.value);
            params.set("end", form.elements.endTime.value);
        }
        return "/calendar/events/new?" + params;
    }

    async function openSheet(root, item) {
        const dialog = root.querySelector("#grid-sheet");
        if (!dialog) return;
        const occurrence = item.dataset.recurrenceId;
        const url = `/calendar/events/${item.dataset.eventId}/sheet`
            + (occurrence ? "?occurrence=" + encodeURIComponent(occurrence) : "");
        try {
            const response = await fetch(url, { headers: { "Accept": "text/html" } });
            if (!response.ok) throw new Error("Der Termin konnte nicht geladen werden.");
            const body = dialog.querySelector("[data-sheet-body]");
            body.innerHTML = await response.text();
            syncScope(body);
            dialog.showModal();
        } catch (error) {
            helpers().showToast(error.message, "error");
        }
    }

    /** Category and members belong to the whole series; the days of a series stay when it moves. */
    function syncScope(sheet) {
        const chosen = sheet.querySelector("[data-sheet-scope]:checked")?.value;
        if (!chosen) return;
        sheet.querySelectorAll("[data-series-only] select, [data-series-only] input").forEach(input => {
            input.disabled = chosen === "OCCURRENCE";
        });
        sheet.querySelectorAll('input[type="date"]').forEach(input => { input.readOnly = chosen === "SERIES"; });
        sheet.querySelectorAll("[data-scope-hint]").forEach(hint => { hint.hidden = hint.dataset.scopeHint !== chosen; });
    }

    function askScope(root, seriesAllowed) {
        const dialog = root.querySelector("#grid-series");
        dialog.querySelector('[data-series-choice="SERIES"]').disabled = !seriesAllowed;
        dialog.querySelector("[data-series-hint]").hidden = seriesAllowed;
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
        const root = event.target.closest("[data-time-grid]");
        if (!root || drag || event.button > 0 || !event.isPrimary
            || event.target.closest("dialog, .cal-toolbar, .cal-legend-row, .tgrid-head, .tgrid-hint")) return;
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

    /** Turns the pressed pointer into a drag: lifts the item (or starts a new one) and shows the ghost. */
    function begin() {
        const g = gesture;
        cancelGesture();
        const { root, item } = g;
        const type = item?.dataset.gridItem;
        const mode = !item ? (g.cell ? "allday-create" : "create")
            : type === "allday" ? (g.handle ? "allday-resize" : "allday-move")
                : type === "chore" ? "chore-move"
                    : g.handle ? "resize-" + g.handle.dataset.handle : "move";
        drag = {
            mode, root, item, id: g.id, touch: g.touch, x0: g.x0, y0: g.y0, x: g.x, y: g.y, moved: false,
            slot: slotMinutes(root), offset: 0, members: (item?.dataset.members || "").split(",").filter(Boolean),
            recurring: item?.dataset.recurring === "true"
        };
        if (item && type !== "allday") {
            drag.origin = { allDay: false, start: parseLocal(item.dataset.start), end: parseLocal(item.dataset.end) };
            const column = g.column || nearest(columns(root), g.x0);
            drag.grab = absolute(column.dataset.date, minuteAt(column, g.y0)) - drag.origin.start;
            drag.column = column;
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
        try { root.setPointerCapture?.(g.id); } catch (e) { /* capture is optional */ }
        helpers().haptic(10);
        update();
    }

    /** Computes the snapped result of the drag and draws the ghost. */
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
                    const date = addDays(nearest(cells(root), d.x).dataset.date, d.offset);
                    d.result = { allDay: true, first: date, last: date };
                    break;
                }
                const column = d.mode === "chore-move" ? d.column : nearest(columns(root), d.x);
                const pointer = absolute(column.dataset.date, minuteAt(column, d.y));
                const duration = d.origin.end - d.origin.start;
                let start = snap(pointer - d.grab, d.slot) + d.offset * DAY_MINUTES;
                if (d.mode === "chore-move") {
                    const dayStart = absolute(d.column.dataset.date, 0);
                    start = Math.max(dayStart, Math.min(dayStart + DAY_MINUTES - duration, start));
                }
                d.result = { allDay: false, start, end: start + duration };
                break;
            }
            case "resize-start":
            case "resize-end": {
                const column = nearest(columns(root), d.x);
                const edge = absolute(column.dataset.date, snap(minuteAt(column, d.y), d.slot));
                d.result = d.mode === "resize-end"
                    ? { allDay: false, start: d.origin.start, end: Math.max(edge, d.origin.start + d.slot) }
                    : { allDay: false, start: Math.min(edge, d.origin.end - d.slot), end: d.origin.end };
                break;
            }
            case "allday-move": {
                if (!overAllDay && !d.recurring && d.y > root.querySelector("[data-allday-row]").getBoundingClientRect().bottom) {
                    const column = nearest(columns(root), d.x);
                    const start = absolute(column.dataset.date, snap(minuteAt(column, d.y), d.slot)) + d.offset * DAY_MINUTES;
                    d.result = { allDay: false, start, end: start + DEFAULT_MINUTES };
                    break;
                }
                const shift = dayNumber(nearest(cells(root), d.x).dataset.date) - dayNumber(d.grabDate) + d.offset;
                d.result = { allDay: true, first: addDays(d.origin.first, shift), last: addDays(d.origin.last, shift) };
                break;
            }
            case "allday-resize": {
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
        const blocked = blockingZone(root, d.members, d.result);
        const label = describe(d.result);
        if (d.result.allDay) {
            const dates = visibleDates(root);
            const visible = d.result.last >= dates[0] && d.result.first <= dates.at(-1);
            const first = d.result.first < dates[0] ? 0 : dates.indexOf(d.result.first);
            const last = d.result.last > dates.at(-1) ? dates.length - 1 : dates.indexOf(d.result.last);
            if (visible) {
                d.ghost = document.createElement("div");
                d.ghost.className = "tgrid-lane-item tgrid-ghost-lane";
                d.ghost.style.gridColumn = `${first + 2} / span ${last - first + 1}`;
                d.ghost.innerHTML = '<span class="occ occ-allday tgrid-item is-ghost"><span class="occ-title"></span></span>';
                d.ghost.querySelector(".occ-title").textContent = d.item?.dataset.title || "Neuer Termin";
                root.querySelector("[data-allday-row]").append(d.ghost);
            }
        } else {
            const date = dateOf(d.result.start);
            const column = columns(root).find(c => c.dataset.date === date)
                || (d.result.start < absolute(visibleDates(root)[0], 0) ? columns(root)[0] : columns(root).at(-1));
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
        d.ghost?.classList.toggle("is-blocked", Boolean(blocked));
        root.querySelectorAll(".tgrid-zone.is-hit").forEach(zone => zone.classList.toggle("is-hit", zone === blocked));
        blocked?.classList.add("is-hit");
        showLabel(blocked ? label + " · gesperrt: " + blocked.dataset.label : label, Boolean(blocked));
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

    /** A blocked zone of a member of the dragged event that the result touches. */
    function blockingZone(root, members, result) {
        if (!members.length) return null;
        const start = result.allDay ? absolute(result.first, 0) : result.start;
        const end = result.allDay ? absolute(result.last, DAY_MINUTES) : result.end;
        for (const column of columns(root)) {
            const dayStart = absolute(column.dataset.date, 0);
            for (const zone of column.querySelectorAll('.tgrid-zone[data-strength="block"]')) {
                if (!members.includes(zone.dataset.member)) continue;
                const from = dayStart + +zone.dataset.startMinute;
                const to = dayStart + +zone.dataset.endMinute;
                if (from < end && to > start) return zone;
            }
        }
        return null;
    }

    /** Holding the item at the left or right edge pages one day per moment into that direction. */
    function trackEdge() {
        const d = drag;
        if (!["move", "allday-move"].includes(d.mode)) return;
        const box = d.root.querySelector(".tgrid").getBoundingClientRect();
        const direction = d.x < box.left + EDGE_PX ? -1 : d.x > box.right - EDGE_PX ? 1 : 0;
        if (direction === d.edgeDirection) return;
        clearInterval(d.edgeTimer);
        d.edgeDirection = direction;
        if (!direction) return;
        d.edgeTimer = setInterval(() => {
            if (!drag) return;
            drag.offset += direction;
            helpers().haptic(6);
            update();
        }, EDGE_DELAY_MS);
    }

    /** Scrolls the grid while the pointer rests near its upper or lower edge. */
    function autoScroll() {
        const d = drag;
        if (d.scrolling) return;
        const scroller = d.root.querySelector("[data-grid-scroll]");
        const speed = () => {
            const box = scroller.getBoundingClientRect();
            if (d.y >= box.top && d.y < box.top + SCROLL_EDGE_PX) return -SCROLL_STEP_PX;
            return d.y > box.bottom - SCROLL_EDGE_PX ? SCROLL_STEP_PX : 0;
        };
        if (!speed()) return;
        d.scrolling = true;
        const step = () => {
            const amount = drag === d ? speed() : 0;
            if (!amount) {
                d.scrolling = false;
                return;
            }
            scroller.scrollTop += amount;
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
        d.root.querySelectorAll(".tgrid-zone.is-relevant, .tgrid-zone.is-hit")
            .forEach(zone => zone.classList.remove("is-relevant", "is-hit"));
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
                openQuick(root, { allDay: false, start: d.anchor, end: d.anchor + DEFAULT_MINUTES });
            } else if (d.mode === "allday-create") {
                openQuick(root, { allDay: true, first: d.anchorDate, last: d.anchorDate });
            } else if (d.touch && d.item) {
                // lifted without moving: show the handles for start and end
                d.item.classList.add("is-selected");
            }
            return;
        }
        if (d.mode === "create" || d.mode === "allday-create") {
            openQuick(root, d.result);
            return;
        }
        const blocked = blockingZone(root, d.members, d.result);
        if (blocked) {
            helpers().showToast(`Dort ist gesperrt (${blocked.dataset.label}). Der Termin bleibt, wo er war.`, "error");
            return;
        }
        if (sameTimes(d.origin, d.result)) return;
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

    async function commit(d) {
        const root = d.root;
        const target = d.result.allDay ? d.result.first : dateOf(d.result.start);
        if (d.mode === "chore-move") {
            const url = `/calendar/actions/chores/${d.item.dataset.choreId}/reschedule`;
            await run(root, url, { start: clock(minuteOf(d.result.start)) },
                { start: clock(minuteOf(d.origin.start)) }, target);
            return;
        }
        let scope = "SINGLE";
        if (d.recurring) {
            const sameDay = !d.result.allDay && !d.origin.allDay && dateOf(d.result.start) === dateOf(d.origin.start);
            scope = await askScope(root, sameDay);
            if (!scope) return;
        }
        const extra = d.recurring ? { scope, recurrenceId: d.item.dataset.recurrenceId } : { scope };
        const url = `/calendar/actions/events/${d.item.dataset.eventId}/reschedule`;
        await run(root, url, { ...timesParams(d.result), ...extra }, { ...timesParams(d.origin), ...extra }, target);
    }

    async function run(root, url, params, undoParams, date) {
        try {
            const result = await post(url, params);
            report(result, async () => {
                await post(url, undoParams);
                reload(root);
            });
        } catch (error) {
            helpers().showToast(error.message, "error");
        }
        reload(root, date);
    }

    /* --- Taps and forms ------------------------------------------------------------------------------------------ */

    function onClick(event) {
        const root = event.target.closest("[data-time-grid]");
        if (!root || event.target.closest("dialog")) return;
        const slotChoice = event.target.closest("[data-slot-choice]");
        if (slotChoice) {
            switchSlot(root, slotChoice.dataset.slotChoice);
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
            if (item.dataset.gridItem !== "chore") {
                event.preventDefault();
                openSheet(root, item);
            }
            return;
        }
        if (event.target.closest(".occ") || root.dataset.canCreate !== "true") return;
        const column = event.target.closest(".tgrid-col");
        if (column) {
            const start = absolute(column.dataset.date, snapDown(minuteAt(column, event.clientY), slotMinutes(root)));
            openQuick(root, { allDay: false, start, end: start + DEFAULT_MINUTES });
            return;
        }
        const cell = event.target.closest(".tgrid-allday-cell");
        if (cell) openQuick(root, { allDay: true, first: cell.dataset.date, last: cell.dataset.date });
    }

    async function onSubmit(event) {
        const form = event.target.closest("form[data-grid-form]");
        if (!form) return;
        // the confirmation dialog asks first and submits again afterwards
        if (form.dataset.confirm && form.dataset.confirmed !== "true") return;
        event.preventDefault();
        delete form.dataset.confirmed;
        const root = form.closest("[data-time-grid]");
        const button = form.querySelector('[type="submit"]');
        if (button) button.disabled = true;
        try {
            const result = await post(form.action, new FormData(form));
            const dialog = form.closest("dialog");
            if (dialog?.open) dialog.close();
            const undo = form.dataset.gridForm === "create" && result.eventId
                ? () => post(`/calendar/actions/events/${result.eventId}/delete`, {}).then(() => reload(root))
                : null;
            report(result, undo);
            reload(root, form.elements.startDate?.value);
        } catch (error) {
            helpers().showToast(error.message, "error");
        } finally {
            if (button) button.disabled = false;
        }
    }

    /* --- Set-up per rendered grid -------------------------------------------------------------------------------- */

    function setUp() {
        const root = document.querySelector("[data-time-grid]");
        if (!root || root.dataset.gridReady) return;
        root.dataset.gridReady = "true";
        if (root.dataset.view === "week" && matchMedia(PHONE).matches) {
            window.location.replace(`/calendar/days3?date=${root.dataset.date}`);
            return;
        }
        applySlot(root, readSlot());
        const scroller = root.querySelector("[data-grid-scroll]");
        root.style.setProperty("--grid-top", Math.round(scroller.getBoundingClientRect().top + window.scrollY) + "px");
        // head and all-day row leave room for the scrollbar so that their columns line up with the hours
        root.style.setProperty("--scrollbar-w", scroller.offsetWidth - scroller.clientWidth + "px");
        if (pendingScroll !== null) {
            scroller.scrollTop = pendingScroll;
            pendingScroll = null;
        } else {
            const today = root.querySelector(".tgrid-col.is-today");
            const minute = today ? Math.max(0, +root.dataset.nowMinute - DEFAULT_MINUTES) : FIRST_HOUR_SHOWN * 60;
            scroller.scrollTop = minute / DAY_MINUTES * scroller.scrollHeight;
        }
        const details = root.querySelector("[data-quick-details]");
        details?.addEventListener("click", () => { details.href = detailsUrl(details.closest("form")); });
    }

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
        document.addEventListener("submit", onSubmit);
        document.addEventListener("change", event => {
            if (event.target.matches("[data-sheet-scope]")) syncScope(event.target.closest("[data-sheet-body]"));
        });
        // while dragging with a finger the page must not scroll; before, it scrolls as usual
        document.addEventListener("touchmove", event => { if (drag?.touch) event.preventDefault(); }, { passive: false });
        // events are links: the browser's own link dragging would cancel the pointer events
        document.addEventListener("dragstart", event => {
            if (event.target.closest?.("[data-time-grid] .tgrid-allday, [data-time-grid] .tgrid-body")) event.preventDefault();
        });
        document.addEventListener("contextmenu", event => {
            if (event.target.closest("[data-time-grid] .tgrid-allday, [data-time-grid] .tgrid-body")) event.preventDefault();
        });
        document.addEventListener("keydown", event => {
            if (event.key === "Escape" && drag) abort();
        });
        document.addEventListener("htmx:afterSettle", setUp);
        setInterval(moveNowLine, 60000);
        setUp();
    });
})();
