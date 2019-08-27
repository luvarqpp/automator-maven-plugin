package com.jamosolutions.automator.help;

import com.jamosolutions.automator.domain.Device;
import com.jamosolutions.automator.domain.TestCase;
import com.jamosolutions.jamoAutomator.domain.Report;
import org.apache.maven.plugin.logging.Log;

import java.util.*;
import java.util.stream.Collectors;

import static com.jamosolutions.automator.help.Colorizer.*;

/**
 * Class responsible for holding agenda around orchestrating test executions (actually delegation to jamo automator
 * service). Instance of this class will hold list of what should be done (for now, pairs of (device,testCase)),
 * what is being executed right now and drive execution using {@link JamoAutomatorClient}.
 */
public class TestOrchestrator {
    private final JamoAutomatorClient jamoAutomatorClient;
    private final Log log;
    private final int retestCount;

    private final Map<Device, List<PlannedTestRun>> executionsToDoFlight = new HashMap<>();
    private final Map<Device, List<TestRun>> executionsInFlight = new HashMap<>();
    private final Map<Device, List<TestRun>> executionsFinished = new HashMap<>();
    private final List<TestRunReporterListener> testRunReporterListeners = new ArrayList<>(3);
    /**
     * Map from execution (should contain executions in flight only) to system time till which given execution
     * should not be timeouted, nor otherwise "stopped". This mechanism is used when client method
     * {@link JamoAutomatorClient#getReport(String)} does return anything strange (HTTP 500 and so on).
     * <p>
     * Method {@link #getReportAndProcessIt(Log, JamoAutomatorClient, TestRun)} is the only one place which can
     * put anything to this map. Removing from map should be done from anywhere, where execution can finish its
     * existence in {@link #executionsInFlight} list. // TODO Perhaps we will ignore "zombies" here.
     */
    // TODO ged rid of this if possible and hide functionality in TestRun itself.
    private final Map<TestRun, Long> operationsSuspendedTillMs = new HashMap<>();

    public TestOrchestrator(JamoAutomatorClient jamoAutomatorClient, Log log, int retestCount) {
        this.jamoAutomatorClient = jamoAutomatorClient;
        this.log = log;
        this.retestCount = retestCount;
    }

    public void addTestForExecution(PlannedTestRun plannedTestRun) {
        this.executionsToDoFlight.compute(plannedTestRun.getDevice(), (k, v) -> {
            if (v == null) {
                v = new ArrayList<>(40);
            }
            v.add(plannedTestRun);
            return v;
        });
    }

    /**
     * @return true, if there is still some unfinished execution on the road, or some executions to be executed. False
     * when all {@link com.jamosolutions.automator.domain.TestCase} has been executed for all defined devices.
     */
    public boolean isStillSomethingNeedToBeDone() {
        return executionsToDoFlight.size() > 0 || executionsInFlight.size() > 0;
    }

    /**
     * Iterates over all idle devices with non-empty to-do list and executes {@link TestRun} according next
     * {@link PlannedTestRun} instance in list.
     * <p>
     * If executing test fails, method does look at {@link #retestCount} and if it is lower than actual failed count
     * of requests for execution, it will plan this failed {@link PlannedTestRun} again. Retried attempts are added
     * to the end of list.
     */
    public void checkForIdleDevicesAndUseThem() {
        Set<Device> idleDevicesWithNonEmptyToDo = new HashSet<>(executionsToDoFlight.keySet());
        idleDevicesWithNonEmptyToDo.removeAll(executionsInFlight.keySet());
        if(idleDevicesWithNonEmptyToDo.isEmpty()) {
            log.debug("There is no idle device with some work in ToDo queue.");
            return;
        }
        //final String idleDevicesWithNonEmptyToDoStr = String.join(", ", idleDevicesWithNonEmptyToDo.stream().map(Device::getName).collect(Collectors.toCollection()));
        final String idleDevicesWithNonEmptyToDoStr = idleDevicesWithNonEmptyToDo
                .stream()
                .map(Device::getName)
                .collect(Collectors.joining(", "));
        log.debug("Going to start executions on devices (" + idleDevicesWithNonEmptyToDoStr + ") without any execution in flight, but with something in ToDo queue.");
        for (Device idleDevice : idleDevicesWithNonEmptyToDo) {
            PlannedTestRun newPlannedTestRun = popAnotherTestForDevice(idleDevice).get();
            // as we have filtered devices, all should have at least single test for given device. We are rude and use Optional.get() without any preceding check
            final TestRun newTestRun = new TestRun(log, jamoAutomatorClient, newPlannedTestRun);
            if (newTestRun.startTest()) {
                this.executionsInFlight.compute(idleDevice, (k, v) -> {
                    if (v == null) {
                        v = new ArrayList<>(2);
                    }
                    v.add(newTestRun);
                    return v;
                });
            } else {
                // execution failed (finished with ExecutionOutcome.EXECERR). Are we allowed to retry exec?
                final boolean willRetry = retestIfNeeded(newTestRun);
                finishTestRunExecution(newTestRun, !willRetry);
            }
        } // end of for each idleDevicesWithNonEmptyToDo
    }

    /**
     * This method will also remove {@link TestRun} instance from {@link #executionsInFlight} map. So device can end
     * with no execution running on it.
     */
    public void getReportsForRunningTests() {
        for (Device device : executionsInFlight.keySet()) {
            this.getReportsForRunningTests(device);
        }
    }

    public void getReportsForRunningTests(Device device) {
        for (Iterator<TestRun> iterator = executionsInFlight.get(device).iterator(); iterator.hasNext(); ) {
            TestRun testRun = iterator.next();
            if (getReportAndProcessIt(log, jamoAutomatorClient, testRun)) {
                removeFromInFlight(iterator, device);
            }
        } // end of for iterator through executionsInFlight
    }

    private void removeFromInFlight(Iterator<TestRun> iterator, Device device) {
        iterator.remove();
        if(executionsInFlight.get(device).size() == 0) {
            // Remove empty list for device.
            log.debug(colorize("Going to remove record from executionsInFlight for device " + device(device) + "."));
            executionsInFlight.remove(device);
        }
    }

    public void checkTimeoutsOnRunningTests() {
        this.executionsInFlight.keySet();
        for (Device device : executionsInFlight.keySet()) {
            this.checkTimeoutsOnRunningTests(device);
        }
    }

    public void checkTimeoutsOnRunningTests(Device device) {
        for (Iterator<TestRun> iterator = executionsInFlight.get(device).iterator(); iterator.hasNext(); ) {
            TestRun testRun = iterator.next();
            if (testRun.checkIfTimeoutHappen()) {
                boolean isFinalRunForPlannedTestRun = !this.retestIfNeeded(testRun);
                finishTestRunExecution(testRun, isFinalRunForPlannedTestRun);
                removeFromInFlight(iterator, device);
            }
        } // end of for iterator through executionsInFlight
    }

    /**
     * Handle retest according {@link PlannedTestRun#getAttemptCount()} from {@link TestRun} and {@link #retestCount}
     * parameter. Method will solve some logging and adding new {@link TestRun} instance to execution list, using
     * {@link #addTestForExecution(PlannedTestRun)}.
     *
     * @param testRun   actual {@link TestRun} which should be checked for "retest" attempt
     * @return  true, if retest was planned, false otherwise
     */
    private boolean retestIfNeeded(TestRun testRun) {
        if(testRun.getExecutionOutcome() == ExecutionOutcome.SUCCESS) {
            throw new RuntimeException("retestIfNeeded called with testRun with Success execution outcome! testRun=" + testRun);
        }
        final Device device = testRun.getPlannedTestRun().getDevice();
        long numberOfTestRunsForPlannedTestRun = testRun.getPlannedTestRun().getAttemptCount();
        if (this.retestCount > numberOfTestRunsForPlannedTestRun) {
            log.info(colorize(
                    "Going to plan retest for device " + device(device) + ", test " +
                            testCase(testRun.getPlannedTestRun().getTestCase()) +
                            " with outcome " + testRun.getExecutionOutcome() + ". Number of test runs till now is @|bold " + numberOfTestRunsForPlannedTestRun +
                            "|@."
            ));
            this.addTestForExecution(testRun.getPlannedTestRun().withIncrementedAttemptCount());
            return true;
        }
        return false;
    }

    /**
     * Method just gets report, save data in internal data structure and report this event using {@link #finishTestRunExecution(TestRun, boolean)}.
     *
     * @param log     log to report (mostly) debug things about progress
     * @param testRun actual {@link TestRun} instance, for which we should look reports for
     * @return returns true, if report has been found and processed. false otherwise. Note that if true is returned,
     * it means that given device should finished running given test.
     */
    private boolean getReportAndProcessIt(Log log, JamoAutomatorClient jamoAutomatorClient, TestRun testRun) {
        Report report;
        final Device device = testRun.getPlannedTestRun().getDevice();
        final TestCase testCase = testRun.getPlannedTestRun().getTestCase();
        try {
            report = jamoAutomatorClient.getReport(testRun.getExecutionId());
        } catch (Exception ex) {
            if (operationsSuspendedTillMs.getOrDefault(testRun, 0L) > System.currentTimeMillis()) {
                operationsSuspendedTillMs.remove(testRun);
                log.debug(colorize(
                        "Device " + device(device) + " have still running test " +
                                testCase(testCase) + " on it (no report found with id " +
                                "@|blue " + testRun.getExecutionId() + "|@). Going to wait, " +
                                "last report request finished with error=" + ex.getMessage()

                ));
                testRun.errorGettingReport();
                final int errorsWhileGettingReport = testRun.getErrorsWhileGettingReport();
                log.debug("Going to ignore that retrieving report has failed. (there are currently " + errorsWhileGettingReport + " report getting errors for current execution)", ex);
                if (errorsWhileGettingReport >= 5) {
                    throw new RuntimeException("Error while getting report has occurred " + errorsWhileGettingReport + " times!", ex);
                }
                if (errorsWhileGettingReport >= 3) {
                    log.warn(colorize(
                            "There were @|bold,red " + errorsWhileGettingReport + "|@ " +
                                    "errors while getting report from jamo. @|bold,yellow Going to suspend execution activities for 7 minutes!|@"
                    ));
                    operationsSuspendedTillMs.put(testRun, System.currentTimeMillis() + (7 * 60 * 1000));
                }
            }
            return false;
        }
        if (report != null) {
            ExecutionOutcome outcome = testRun.setReport(report);
            boolean isFinalRunForPlannedTestRun = true;
            if (outcome != ExecutionOutcome.SUCCESS) {
                isFinalRunForPlannedTestRun = !this.retestIfNeeded(testRun);
            }
            finishTestRunExecution(testRun, isFinalRunForPlannedTestRun);
            return true;
        } else {
            log.debug(
                    colorize(
                            "Device " + device(device) + " have still running test " +
                                    "@|blue " + testCase + "|@ on it (no report found with id " +
                                    "@|blue " + testRun.getExecutionId() + "|@). Going to wait."

                    )
            );
            return false;
        }
    }

    /**
     * Record result of {@link TestRun} and also pass it (in sync, blocking way) to all registered listeners.
     *
     * @param testRun                     {@link TestRun} instance with result (report) filled in. Note that all
     *                                    {@link PlannedTestRun} instances should get at least single {@link TestRun} instance
     * @param isFinalRunForPlannedTestRun if you plan to rerun given {@link PlannedTestRun}, fill here false. True
     *                                    otherwise
     * @see #testRunReporterListeners
     */
    private void finishTestRunExecution(TestRun testRun, boolean isFinalRunForPlannedTestRun) {
        final Device device = testRun.getPlannedTestRun().getDevice();
        // TODO add reporting here. Online progress logging, csv file exporter and also final xml report filling. Hook some "reporters" here?
        executionsFinished.compute(device, (k, v) -> {
            if (v == null) {
                v = new ArrayList<>(1);
            }
            v.add(testRun);
            return v;
        });
        for (TestRunReporterListener testRunReporterListener : testRunReporterListeners) {
            testRunReporterListener.finishTestRunExecution(testRun, isFinalRunForPlannedTestRun);
        }
    }

    /**
     * Counts number of finished {@link TestRun} instances, not counting multiple runs (retries) for same
     * {@link PlannedTestRun}.
     *
     * @return number of "solved" {@link PlannedTestRun} instances from original request recipe
     */
    public int getFinishedPlannedTestRunsCount() {
        long sum = 0;
        for (List<TestRun> testRunListForSomeDevice : executionsFinished.values()) {
            sum += testRunListForSomeDevice.stream().map(TestRun::getPlannedTestRun).collect(Collectors.toSet()).size();
        }
        return 0;
    }

    /**
     * Get AND remove any future execution instance for given device.
     */
    private Optional<PlannedTestRun> popAnotherTestForDevice(Device device) {
        // futureExecutions.stream().filter(e -> e.device.equals(device)).findAny();
        for (Iterator<PlannedTestRun> futureExecutionsIterator = executionsToDoFlight.get(device).iterator(); futureExecutionsIterator.hasNext(); ) {
            PlannedTestRun fe = futureExecutionsIterator.next();
            futureExecutionsIterator.remove();
            if(executionsToDoFlight.get(device).size() == 0) {
                // Remove empty list for device.
                log.debug(colorize("Going to remove record from executionsToDoFlight for device " + device(device) + "."));
                executionsToDoFlight.remove(device);
            }
            return Optional.of(fe);
        }
        return Optional.empty();
    }

    /**
     * Returns internal list of reporters. If you want to remove something from given list, be sure to remove only
     * instances added by you.
     *
     * @return internal list of listeners, which gets notification about finished test runs
     */
    public List<TestRunReporterListener> getTestRunReporterListeners() {
        return testRunReporterListeners;
    }

    public int getExecutionsToDoFlightSize() {
        int sum = 0;
        for (Iterator<List<PlannedTestRun>> it = executionsToDoFlight.values().iterator(); it.hasNext(); ) {
            sum += it.next().size();
        }
        return sum;
    }

    public int getExecutionsInFlightSize() {
        int sum = 0;
        for (Iterator<List<TestRun>> it = executionsInFlight.values().iterator(); it.hasNext(); ) {
            sum += it.next().size();
        }
        return sum;
    }
}
