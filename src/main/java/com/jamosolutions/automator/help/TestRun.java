package com.jamosolutions.automator.help;

import com.jamosolutions.automator.domain.Device;
import com.jamosolutions.automator.domain.ResponseStringWrapper;
import com.jamosolutions.automator.domain.TestCase;
import com.jamosolutions.jamoAutomator.domain.Report;
import org.apache.maven.plugin.logging.Log;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static com.jamosolutions.automator.help.Colorizer.*;

/**
 * TestRun, i.e. instance of one test execution, single "cycle".
 */
public class TestRun {
    private final Map<Device, String> lastInfoLogPerDevice = new HashMap<>();
    private final Log log;

    /**
     * Link to {@link PlannedTestRun} instance, upon which this {@link TestRun} has been created.
     */
    private final PlannedTestRun plannedTestRun;
    private final JamoAutomatorClient jamoAutomatorClient;
    private String executionId;
    /**
     * Time, when {@link TestRun} instance is created. It should be created just after request to execute test,
     * is finished.
     */
    private long execRequestReturnedTimeMillis;
    private long requestStartTime;
    /**
     * Finished time from maven plugin point of view. It means time ({@link System#currentTimeMillis()} from point
     * of time when {@link #getExecutionOutcome()} has been figured out.
     */
    private long finishedTimeMillis;
    private int errorsWhileGettingReport = 0;
    /**
     * Null if, execution is still running.
     */
    private Report report;
    /**
     * Response from last run request to jamo automator service. It should be filled in case there was an attempt
     * to {@link #startTest()}.
     *
     * @see JamoAutomatorClient#runTestCaseBare(Device, TestCase)
     */
    private ResponseStringWrapper execRequestResponse;
    private Exception execRequestException;
    private ExecutionOutcome executionOutcome = null;

    public TestRun(Log log, JamoAutomatorClient jamoAutomatorClient, PlannedTestRun plannedTestRun) {
        this.log = log;
        this.plannedTestRun = plannedTestRun;
        this.jamoAutomatorClient = jamoAutomatorClient;
    }

    /**
     * Make {@link JamoAutomatorClient} to request execution of this {@link TestRun}.
     *
     * @return true if execution was successful; false otherwise
     */
    public boolean startTest() {
        this.requestStartTime = System.currentTimeMillis();
        final Device device = this.plannedTestRun.getDevice();
        final TestCase testCase = this.plannedTestRun.getTestCase();
        try {
            this.execRequestResponse = jamoAutomatorClient.runTestCase(device, testCase);
            this.executionId = this.execRequestResponse.getMessage();
        } catch (Exception ex) {
            this.setExecutionOutcome(ExecutionOutcome.EXECERR);
            // TODO count errors in needToExecute and stop it after some number of exceptions.
            this.execRequestException = ex;
            logInfoForDevice(
                    device,
                    colorize(
                            "Failed to execute test case on device " + device(device) + ", test case " +
                                    testCase(testCase) + ". Going to try next round."

                    )
            );
            return false;
        } finally {
            this.execRequestReturnedTimeMillis = System.currentTimeMillis();
        }
        if(!this.execRequestResponse.isSuccess()) {
            this.setExecutionOutcome(ExecutionOutcome.EXECERR);
        }
        return this.execRequestResponse.isSuccess();
    }

    public String getExecutionId() {
        return executionId;
    }

    public long getExecRequestReturnedTimeMillis() {
        return this.execRequestReturnedTimeMillis;
    }

    public long getRequestStartTime() {
        return requestStartTime;
    }

    public void errorGettingReport() {
        this.errorsWhileGettingReport++;
    }

    public int getErrorsWhileGettingReport() {
        return this.errorsWhileGettingReport;
    }

    public long getFinishedTimeMillis() {
        return finishedTimeMillis;
    }

    public ExecutionOutcome getExecutionOutcome() {
        return this.executionOutcome;
    }

    /**
     * Method for "finishing" this {@link TestRun} instance. This method have to be called only once for each instance
     * and have to be called just after finishing execution of {@link TestRun}.
     * <p>
     * Records current time ({@link System#currentTimeMillis()} into {@link #finishedTimeMillis} attribute and saves
     * given {@link ExecutionOutcome} into {@link #executionOutcome} attribute.
     *
     * @param executionOutcome result of this {@link TestRun} instance
     */
    public void setExecutionOutcome(ExecutionOutcome executionOutcome) {
        if (this.executionOutcome != null) {
            throw new RuntimeException("ExecutionOutcome can be set only once and should be set just after finishing execution of given TestCase instance!");
        }
        this.finishedTimeMillis = System.currentTimeMillis();
        this.executionOutcome = executionOutcome;
    }

    public PlannedTestRun getPlannedTestRun() {
        return plannedTestRun;
    }

    private void logInfoForDevice(Device device, String message) {
        String lastLog = lastInfoLogPerDevice.get(device);
        if (!Objects.equals(lastLog, message)) {
            lastInfoLogPerDevice.put(device, message);
            log.info(message);
        }
    }

    public Report getReport() {
        return report;
    }

    /**
     * Method saves report in internal attribute which is accessible by {@link #getReport()} method. It will make report
     * status available using {@link #getExecutionOutcome()} method. it internally goes into report using
     * {@link Report#getStatus()} method to get actual report status.
     *
     * @param report report for the {@link TestRun}
     * @return ExecutionOutcome for given report. It can be obtained also by using {@link #getExecutionOutcome()} method
     */
    public ExecutionOutcome setReport(Report report) {
        this.report = report;
        if (this.report.getStatus() == 0) {
            this.setExecutionOutcome(ExecutionOutcome.SUCCESS);
        } else {
            this.setExecutionOutcome(ExecutionOutcome.FAILURE);
        }
        return this.getExecutionOutcome();
    }

    public ResponseStringWrapper getExecRequestResponse() {
        return execRequestResponse;
    }

    public Exception getExecRequestException() {
        return execRequestException;
    }

    /**
     * Check for timeout (with respect to temporal network outages and so on). if timeout happens, change internal state
     * according to it (fill in {@link #finishedTimeMillis} attribute and also set right {@link #executionOutcome}).
     * <p>
     * NOTE: This is NOT idempotent call!
     *
     * @return true only once (when timeout happens, or is accepted).
     */
    public boolean checkIfTimeoutHappen() {
        if (this.executionOutcome != null) {
            throw new RuntimeException("Outcome is filled in already. Do not check for timeout in this state! executionOutcome=" + this.executionOutcome);
        }
        // TODO add some delay (benevolence) here, if getting report from jamo has failed (due to network or service exception)
        long durationTillNowMs = (System.currentTimeMillis() - this.getExecRequestReturnedTimeMillis());
        final boolean hasTimeouted = durationTillNowMs > this.getPlannedTestRun().getTestCase().getTimeout() * 60 * 1000;
        if (hasTimeouted) {
            this.setExecutionOutcome(ExecutionOutcome.TIMEOUT);
        }
        return hasTimeouted;
    }

    @Override
    public String toString() {
        return "TestRun(plannedTestRun=" + this.getPlannedTestRun() + ")";
    }
}
