package com.jamosolutions.automator.reporters;

import com.jamosolutions.automator.domain.Device;
import com.jamosolutions.automator.domain.TestCase;
import com.jamosolutions.automator.help.TestOrchestrator;
import com.jamosolutions.automator.help.TestRun;
import com.jamosolutions.automator.help.TestRunReporterListener;
import com.jamosolutions.jamoAutomator.domain.Report;
import org.apache.maven.plugin.logging.Log;

import java.util.Date;

import static com.jamosolutions.automator.help.Colorizer.*;

/**
 * This class is intend to be mostly logger (logging progress and also some simple summary report to console). Class
 * uses {@link TestRunReporterListener} interface to receive events about finished {@link TestRun}'s.
 */
public class OnlineLogTestRunExecReport implements TestRunReporterListener {
    public static final String COLOR_SUCCESS = "green,bold";
    public static final String COLOR_TIMEOUT = "faint,underline,red,bold";
    public static final String COLOR_EXECERR = "yellow,bold";
    public static final String COLOR_FAILURE = "red,bold";
    private static final String COLOR_RETRIED = "italic";

    private final Log log;

    private int nbOfTimeouts = 0;
    private int nbOfExecErrors = 0;
    private int nbOfTestFailures = 0;
    private int nbOfSuccess = 0;
    private int nbOfRetryLater;

    public OnlineLogTestRunExecReport(Log log) {
        this.log = log;
    }

    public void logTestRunSuccess(TestRun testRun) {
        final Report report = testRun.getReport();
        final Device device = testRun.getPlannedTestRun().getDevice();
        final TestCase testCase = testRun.getPlannedTestRun().getTestCase();
        log.info(colorize(
                "@|" + COLOR_SUCCESS + " Success|@ test (" + device(device) + ";" + testCase(testCase) + ")"
        ));
        final long now = System.currentTimeMillis();
        final long wallDurationMs = now - testRun.getRequestStartTime();
        log.debug(
                "Success debug info:\n" +
                        "wallDuration(our): " + wallDurationMs + "\n" +
                        "executeRequestFinishedAt(our): " + new Date(testRun.getExecRequestReturnedTimeMillis()) + "\n" +
                        "currentTime(our): " + new Date() + "\n" +
                        "execution.getExecutionId: " + testRun.getExecutionId() + "\n" +
                        "getErrorsWhileGettingReport: " + testRun.getErrorsWhileGettingReport() + "\n" +
                        "report.getCreationDate: " + report.getCreationDate() + "\n" +
                        "report.getEndDate: " + report.getEndDate() + "\n" +
                        "report.getBuildNumber: " + report.getBuildNumber() + "\n" +
                        "report.getKeyString: " + report.getKeyString() + "\n" +
                        "report.getExecutionId: " + report.getExecutionId()
        );
        if (wallDurationMs * 3 < (testCase.getTimeout() * 60 * 1000)) {
            log.info(colorize(
                    "@|yellow,bold Consider lowering timeout for testcase.|@ Test has successfully finished under less than third of its timeout time. " +
                            testCase(testCase) + ", " +
                            "timeout: @|bold " + (testCase.getTimeout() * 60 * 1000) + "|@ milliseconds, " +
                            "actual duration: @|bold " + (wallDurationMs) + "|@ milliseconds. (Note, that timeout in configuration is in minutes.)"
            ));
        }
    }

    public void logTestRunTimeout(TestRun testRun, boolean willBeRetried) {
        final Device device = testRun.getPlannedTestRun().getDevice();
        final TestCase testCase = testRun.getPlannedTestRun().getTestCase();
        log.warn(colorize(
                "@|" + COLOR_TIMEOUT + " Timeout test execution|@ test (" + device(device) + ";" + testCase(testCase) + ")"
        ));
        // TODO do not use "now", instead calculate all things directly from report/testRun data!
        final long now = System.currentTimeMillis();
        final long durationTillNowMs = now - testRun.getExecRequestReturnedTimeMillis();
        log.debug(
                "Timeout debug info:\n" +
                        "wallDuration(our): " + (now - testRun.getRequestStartTime()) + "\n" +
                        "executeRequestFinishedAt(our): " + new Date(testRun.getExecRequestReturnedTimeMillis()) + "\n" +
                        "currentTime(our): " + new Date() + "\n" +
                        "execution.getExecutionId: " + testRun.getExecutionId() + "\n" +
                        "getErrorsWhileGettingReport: " + testRun.getErrorsWhileGettingReport() + "\n" +
                        "durationTillNowMs(calculated): " + durationTillNowMs + "\n" +
                        "execution.getTestCase().getTimeout(): " + testCase.getTimeout() + " -> " + (60 * 1000 * testCase.getTimeout()) + "\n" +
                        "willBeRetried: " + willBeRetried + "\n"
        );
    }

    public void logTestRunExecError(TestRun testRun, boolean willBeRetried) {
        final Device device = testRun.getPlannedTestRun().getDevice();
        final TestCase testCase = testRun.getPlannedTestRun().getTestCase();
        final String runResponseMsg = testRun.getExecRequestResponse() == null ?
                "execException=" + testRun.getExecRequestException().getMessage() :
                testRun.getExecRequestResponse().getMessage();
        final long requestStartTime = testRun.getRequestStartTime();
        log.warn(colorize(
                "@|" + COLOR_EXECERR + " error while executing|@ test (" + device(device) + ";" + testCase(testCase) + "), " +
                        "message: @|" + COLOR_EXECERR + " " + runResponseMsg + "|@"
        ));
        final long now = System.currentTimeMillis();
        log.debug(
                "executing error debug info:\n" +
                        "wallDuration(our): " + (now - requestStartTime) + "\n" +
                        "requestStartTime(our): " + requestStartTime + "\n" +
                        "currentTime(our): " + new Date() + "\n" +
                        "willBeRetried: " + willBeRetried + "\n" +
                        "response:" + testRun.getExecRequestResponse()
        );
    }

    public void logTestRunFailure(TestRun testRun, boolean willBeRetried) {
        final Report report = testRun.getReport();
        final Device device = testRun.getPlannedTestRun().getDevice();
        final TestCase testCase = testRun.getPlannedTestRun().getTestCase();
        log.warn(colorize(
                "@|" + COLOR_FAILURE + " Failure test execution|@ test (" + device(device) + ";" + testCase(testCase) + "), " +
                        "reportLink: " + report.getReportUrl()
        ));
        final long now = System.currentTimeMillis();
        log.debug(
                "Failure test debug info:\n" +
                        "wallDuration(our): " + (now - testRun.getRequestStartTime()) + "\n" +
                        "executeRequestFinishedAt(our): " + new Date(testRun.getExecRequestReturnedTimeMillis()) + "\n" +
                        "currentTime(our): " + new Date() + "\n" +
                        "execution.getExecutionId: " + testRun.getExecutionId() + "\n" +
                        "getErrorsWhileGettingReport: " + testRun.getErrorsWhileGettingReport() + "\n" +
                        "report.getCreationDate: " + report.getCreationDate() + "\n" +
                        "report.getEndDate: " + report.getEndDate() + "\n" +
                        "report.getBuildNumber: " + report.getBuildNumber() + "\n" +
                        "report.getKeyString: " + report.getKeyString() + "\n" +
                        "report.getExecutionId: " + report.getExecutionId() + "\n" +
                        "report.getStatus: " + report.getStatus() + "\n" +
                        "willBeRetried: " + willBeRetried + "\n" +
                        "linkToReport: " + report.getReportUrl()
        );
    }

    public int getNbOfExecErrors() {
        return this.nbOfExecErrors;
    }

    public int getNbOfSuccess() {
        return nbOfSuccess;
    }

    public int getNbOfTestFailures() {
        return nbOfTestFailures;
    }

    public int getNbOfTimeouts() {
        return nbOfTimeouts;
    }

    /**
     * Sums all executions (successfull or not). It returns sum of all four internal counters.
     *
     * @return number of tests in executed suite. it counts also things like non-existent reports (which does not executed)
     */
    public int getTotalExecutionsAttempts() {
        return this.getNbOfSuccess() + this.getNbOfTestFailures() + this.getNbOfExecErrors() + this.getNbOfTimeouts() + this.getNbOfRetryLater();
    }

    private int getNbOfRetryLater() {
        return this.nbOfRetryLater;
    }

    /**
     * @return sum of execution errors (nonexistent test,...) and test timeouts (no report found within time)
     */
    public int getNbOfErrors() {
        return this.getNbOfExecErrors() + this.getNbOfTimeouts();
    }

    public int getNbOfAllNegativeResults() {
        return this.getNbOfExecErrors() + this.getNbOfTimeouts() + this.getNbOfTestFailures();
    }

    public void logProgressReport(int waitRound, int executionsToDoFlight, int executionsInFlight) {
        if (waitRound == 0) {
            log.info(colorize(
                    "\t@|bg_black,bold,cyan status legend|@(" +
                            "@|bold,underline TotalExecutionsAtemps done|@(" +
                            "@|" + OnlineLogTestRunExecReport.COLOR_SUCCESS + " success|@/" +
                            "@|" + OnlineLogTestRunExecReport.COLOR_FAILURE + " failures|@/" +
                            "@|" + OnlineLogTestRunExecReport.COLOR_EXECERR + " execErrs|@/" +
                            "@|" + OnlineLogTestRunExecReport.COLOR_TIMEOUT + " timeouts|@), " +
                            "@|bold XX waiting|@, " +
                            "@|italic,underline XX inProcess|@, " +
                            "@|italic XX retried|@)."
            ));
        }
        log.info(colorize(
                "\t@|bg_black,bold,cyan status|@(" +
                        "@|bold,underline " + this.getTotalExecutionsAttempts() + " done|@(" +
                        "@|" + OnlineLogTestRunExecReport.COLOR_SUCCESS + " " + this.getNbOfSuccess() + "|@/" +
                        "@|" + OnlineLogTestRunExecReport.COLOR_FAILURE + " " + this.getNbOfTestFailures() + "|@/" +
                        "@|" + OnlineLogTestRunExecReport.COLOR_EXECERR + " " + this.getNbOfExecErrors() + "|@/" +
                        "@|" + OnlineLogTestRunExecReport.COLOR_TIMEOUT + " " + this.getNbOfTimeouts() + "|@), " +
                        "@|bold " + executionsToDoFlight + " waiting|@, " +
                        "@|italic,underline " + executionsInFlight + " inProcess|@, " +
                        "@|italic,underline " + this.nbOfRetryLater + " retried|@) " +
                        "I have waited about " + (waitRound * 5) + " seconds for reports till now. Going to wait another 5 seconds."
        ));
    }

    public void logSummaryReport() {
        String headlineColor = "red";
        if (this.getNbOfAllNegativeResults() == 0) {
            headlineColor = "green";
        } else {
            log.warn("There was some negative test results (" + this.getNbOfAllNegativeResults() + ")! See info report bellow for summary or have a look at log or test report file.");
        }
        log.info(colorize(
                "@|bold," + headlineColor + " SUMMARY REPORT|@\n" +
                        "\t@|" + OnlineLogTestRunExecReport.COLOR_SUCCESS + " " +
                        "recordSuccess|@ : @|bold " + this.getNbOfSuccess() + "|@\t (number of test executions with successful execution and asserts)\n" +
                        "\t@|" + OnlineLogTestRunExecReport.COLOR_FAILURE + " " +
                        "failure|@ : \t@|bold " + this.getNbOfTestFailures() + "|@\t (number of tests with report with some failure. Failed on test asserts or failure during test execution)\n" +
                        "\t@|" + OnlineLogTestRunExecReport.COLOR_EXECERR + " " +
                        "exec err|@: \t@|bold " + this.getNbOfExecErrors() + "|@\t (number of executions failed. It counts executions of nonexistent tests, problems with authentication to jamo during test executions and so on)\n" +
                        "\t@|" + OnlineLogTestRunExecReport.COLOR_TIMEOUT + " " +
                        "recordTimeout|@ : @|bold " + this.getNbOfTimeouts() + "|@\t (report not found within recordTimeout after test execution started)\n" +
                        "\t@|" + OnlineLogTestRunExecReport.COLOR_RETRIED + " " +
                        "retried|@ : \t@|bold " + this.getNbOfRetryLater() + "|@\t (number of test runs which has been retried due failure of any kind. These test runs are not presented in junit xml output, but you can find them in csv output with willBeRetried=true)\n" +
                        "\t@|bold " +
                        "total|@ : \t@|bold " + this.getTotalExecutionsAttempts() + "|@\t (just sum of previous numbers)"
        ));
    }

    @Override
    public void finishTestRunExecution(TestRun testRun, boolean isFinalRunForPlannedTestRun) {
        switch (testRun.getExecutionOutcome()) {
            case SUCCESS:
                this.nbOfSuccess++;
                this.logTestRunSuccess(testRun);
                break;
            case FAILURE:
                int a = isFinalRunForPlannedTestRun ? this.nbOfTestFailures++ : this.nbOfRetryLater++;
                this.logTestRunFailure(testRun, !isFinalRunForPlannedTestRun);
                break;
            case TIMEOUT:
                int b = isFinalRunForPlannedTestRun ? this.nbOfTimeouts++ : this.nbOfRetryLater++;
                this.logTestRunTimeout(testRun, !isFinalRunForPlannedTestRun);
                break;
            case EXECERR:
                int c = isFinalRunForPlannedTestRun ? this.nbOfExecErrors++ : this.nbOfRetryLater++;
                this.logTestRunExecError(testRun, !isFinalRunForPlannedTestRun);
                break;
        }
    }

    public void logProgressReport(int waitRound, TestOrchestrator testOrchestrator) {
        this.logProgressReport(waitRound, testOrchestrator.getExecutionsToDoFlightSize(), testOrchestrator.getExecutionsInFlightSize());
    }
}
