package dev.xave.reader;

import static org.junit.Assert.assertEquals;

import org.json.JSONObject;
import org.junit.Test;

public final class AutoSaveTest {
    private static JSONObject post(int posts, boolean linked) throws Exception {
        return new JSONObject().put("article", false).put("status", true).put("posts", posts).put("linked", linked).put("articleLength", 0);
    }

    private static JSONObject article(int length) throws Exception {
        return new JSONObject().put("article", true).put("status", true).put("posts", 0).put("linked", false).put("articleLength", length);
    }

    @Test public void aSinglePostSavesOnceItHasStayedLoadedForTwoMorePolls() throws Exception {
        AutoSave auto = new AutoSave(0);
        assertEquals(AutoSave.Step.WAIT, auto.next(post(0, false), 1000));
        assertEquals(AutoSave.Step.WAIT, auto.next(post(1, true), 2000));
        assertEquals(AutoSave.Step.WAIT, auto.next(post(1, true), 3000));
        assertEquals(AutoSave.Step.SAVE, auto.next(post(1, true), 4000));
    }

    @Test public void aThreadWaitsForCaptureInsteadOfSaving() throws Exception {
        AutoSave auto = new AutoSave(0);
        assertEquals(AutoSave.Step.WAIT, auto.next(post(1, true), 1000));
        assertEquals(AutoSave.Step.THREAD, auto.next(post(3, true), 2000));
    }

    @Test public void anArticleSavesOnlyAfterItsTextStopsGrowing() throws Exception {
        AutoSave auto = new AutoSave(0);
        assertEquals(AutoSave.Step.WAIT, auto.next(article(400), 1000));
        assertEquals(AutoSave.Step.WAIT, auto.next(article(400), 2000));
        assertEquals(AutoSave.Step.WAIT, auto.next(article(2600), 3000));
        assertEquals(AutoSave.Step.WAIT, auto.next(article(2600), 4000));
        assertEquals(AutoSave.Step.SAVE, auto.next(article(2600), 5000));
    }

    @Test public void aPostOtherThanTheLinkedOneNeverCountsAsLoaded() throws Exception {
        AutoSave auto = new AutoSave(0);
        for (int second = 1; second < 20; second++)
            assertEquals(AutoSave.Step.WAIT, auto.next(post(1, false), second * 1000));
    }

    @Test public void aPageThatNeverLoadsIsCapturedAtTheDeadlineSoItsErrorShows() throws Exception {
        AutoSave auto = new AutoSave(5000);
        assertEquals(AutoSave.Step.WAIT, auto.next(post(0, false), 24999));
        assertEquals(AutoSave.Step.SAVE, auto.next(post(0, false), 25000));
    }
}
