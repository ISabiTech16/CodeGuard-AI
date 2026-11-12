package com.codeguard.dto;

import java.util.List;

/**
 * What CodeGuard actually observed when it tried to run the project's tests at the reviewed commit.
 *
 * <p>Honesty contract: {@code status == PASSED} is only ever produced when the test command exited 0
 * AND at least one test was positively counted as executed. Every other outcome is reported as exactly
 * what it was, so "not run" can never read as "passed".</p>
 *
 * status: PASSED, FAILED, ERROR (command failed before/without test results), TIMED_OUT, NOT_RUN,
 * NO_TESTS_EXECUTED (build ok, zero tests ran), UNVERIFIED (exit 0 but counts unreadable).
 */
public record TestExecutionEvidence(
        String status,
        String reason,
        String message,
        String commitSha,
        String command,
        int exitCode,
        long durationMs,
        int testsRun,
        int passed,
        int failed,
        int errors,
        int skipped,
        String countsSource,
        List<FailedTest> failedTests,
        List<ChangedTestResult> changedTests,
        String outputExcerpt,
        String executedAt
) {
    public static TestExecutionEvidence notRun(String reason, String message, String sha) {
        return new TestExecutionEvidence("NOT_RUN", reason, message, sha, "", -1, 0, 0, 0, 0, 0, 0, "NONE",
                List.of(), List.of(), "", null);
    }

    public boolean executed() {
        return !"NOT_RUN".equals(status);
    }

    public boolean passedVerified() {
        return "PASSED".equals(status);
    }

    public TestExecutionEvidence withChangedTests(List<ChangedTestResult> changed) {
        return new TestExecutionEvidence(status, reason, message, commitSha, command, exitCode, durationMs, testsRun,
                passed, failed, errors, skipped, countsSource, failedTests, changed, outputExcerpt, executedAt);
    }

    public record FailedTest(String suite, String name, String message) {}

    /** status: PASSED, FAILED, NOT_EXECUTED, UNKNOWN */
    public record ChangedTestResult(String file, String status, int testsRun, int failures) {}
}
