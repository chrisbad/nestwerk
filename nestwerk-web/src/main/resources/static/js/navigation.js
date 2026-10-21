/* Nestwerk – excursions and the way back: links marked data-excursion carry the current page as "back", the last
   visited calendar view is remembered as fallback for pages that were opened without an origin. */
(() => {
    "use strict";

    const LAST_CALENDAR_KEY = "nw.lastCalendar";
    const CALENDAR_PATH = "/calendar";

    const here = () => window.location.pathname + window.location.search;

    function remember(key, value) {
        try { localStorage.setItem(key, value); } catch (e) { /* only a convenience */ }
    }

    function recall(key) {
        try { return localStorage.getItem(key); } catch (e) { return null; }
    }

    const isCalendarView = () => document.querySelector("#calendar[data-view]") !== null;

    function rememberCalendar() {
        if (isCalendarView()) remember(LAST_CALENDAR_KEY, here());
    }

    /** Back links of pages opened without an origin lead to the last calendar view the user looked at. */
    function useLastCalendar() {
        const last = recall(LAST_CALENDAR_KEY);
        if (!last || !last.startsWith(CALENDAR_PATH)) return;
        document.querySelectorAll('a[data-back-fallback="calendar"]').forEach(link => { link.href = last; });
    }

    function onClick(event) {
        const link = event.target.closest("a[data-excursion]");
        if (!link) return;
        const url = new URL(link.href, window.location.origin);
        if (!url.searchParams.has("back")) url.searchParams.set("back", here());
        link.href = url.pathname + url.search;
    }

    function setUp() {
        rememberCalendar();
        useLastCalendar();
    }

    document.addEventListener("DOMContentLoaded", () => {
        document.addEventListener("click", onClick, true);
        document.addEventListener("htmx:afterSettle", setUp);
        setUp();
    });
})();
