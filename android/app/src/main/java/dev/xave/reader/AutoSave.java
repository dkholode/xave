package dev.xave.reader;

import org.json.JSONObject;

/** Decides, from the capture screen's polled page summary, when a link can save without a tap. */
final class AutoSave {
    enum Step { WAIT, SAVE, THREAD }

    // Polls run once a second, so two unchanged polls in a row mean the page has been still for about two seconds.
    private static final int STEADY_POLLS = 2;
    private static final long TIMEOUT_MS = 20000;

    private final long deadline;
    private String shape;
    private int steady;

    AutoSave(long now) { deadline = now + TIMEOUT_MS; }

    Step next(JSONObject found, long now) {
        boolean article = found.optBoolean("article");
        // Only scrolling loads a whole thread, so saving one on its own would keep just the first screen of it.
        if (!article && found.optInt("posts") > 1) return Step.THREAD;
        String current = article ? "article " + found.optInt("articleLength") : found.optBoolean("linked") ? "post" : null;
        steady = current != null && current.equals(shape) ? steady + 1 : 0;
        shape = current;
        // Past the deadline, capture anyway: it saves a page that never settled, or its error says what is missing.
        return steady >= STEADY_POLLS || now >= deadline ? Step.SAVE : Step.WAIT;
    }
}
