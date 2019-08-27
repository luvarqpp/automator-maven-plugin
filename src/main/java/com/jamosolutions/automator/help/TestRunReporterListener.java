package com.jamosolutions.automator.help;

public interface TestRunReporterListener {
    void finishTestRunExecution(TestRun testRun, boolean isFinalRunForPlannedTestRun);
}
