package com.froglike6.continuityfixture;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

public final class PipelineObservationTest {
    public static void main(String[] args) {
        List<String> receipts = new ArrayList<>();
        Iterator<String> ids = Arrays.asList("observation-1", "observation-2", "observation-2").iterator();
        FixtureActivity.PipelineObservation journal = new FixtureActivity.PipelineObservation(
                new FixtureActivity.PipelineObservation.Sink() {
                    @Override public void record(String id, String phase) { receipts.add(id + "|" + phase); }
                }, new FixtureActivity.PipelineObservation.IdSource() {
                    @Override public String next() { return ids.next(); }
                });

        FixtureActivity.PipelineObservation.Action returned = journal.begin();
        returned.runnableEntered();
        returned.writeReturned();
        check(receipts.equals(Arrays.asList(
                "observation-1|scheduled",
                "observation-1|runnable_entered",
                "observation-1|write_returned")), "returned ordering and identity");

        FixtureActivity.PipelineObservation.Action failed = journal.begin();
        failed.runnableEntered();
        failed.writeFailed(new SecurityException("fixture"));
        check(receipts.subList(3, 6).equals(Arrays.asList(
                "observation-2|scheduled",
                "observation-2|runnable_entered",
                "observation-2|write_failed:java.lang.SecurityException")), "failed ordering and identity");

        expectIllegalState(new Runnable() { @Override public void run() { journal.begin(); } }, "duplicate observation id");
        expectIllegalState(new Runnable() { @Override public void run() { returned.writeReturned(); } }, "duplicate terminal");
        FixtureActivity.PipelineObservation.Action reordered = new FixtureActivity.PipelineObservation(
                new FixtureActivity.PipelineObservation.Sink() {
                    @Override public void record(String id, String phase) { }
                }, new FixtureActivity.PipelineObservation.IdSource() {
                    @Override public String next() { return "observation-3"; }
                }).begin();
        expectIllegalState(new Runnable() { @Override public void run() { reordered.writeReturned(); } }, "terminal before runnable");
        System.out.println("FIXTURE_PIPELINE_OBSERVATION_OK cases=5 returned_order,failed_order,unique_id,duplicate_terminal,reordered_terminal");
    }

    private static void expectIllegalState(Runnable action, String label) {
        try {
            action.run();
            throw new AssertionError(label);
        } catch (IllegalStateException expected) {
        }
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
