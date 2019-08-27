package com.jamosolutions.automator.help;

import com.jamosolutions.automator.domain.Device;
import com.jamosolutions.automator.domain.TestCase;

/**
 * Test to be executed. Each execution will create {@link TestRun} instance.
 * <p>
 * Note: {@link PlannedTestRun} and {@link TestRun} does have 1:N multiplicity. I.e. there can be more than single
 * {@link TestRun} instance from {@link PlannedTestRun}. It can happen when there is retesting enabled and some test
 * fails on first run. It is than re-executed later on same (for now) target device.
 */
public class PlannedTestRun {
    private Device device;
    private TestCase testCase;

    /**
     * Also can be understand as "cycle" for test run.
     */
    private final int attemptCount;

    public PlannedTestRun(Device device, TestCase testCase) {
        this.device = device;
        this.testCase = testCase;
        this.attemptCount = 0;
    }

    private PlannedTestRun(Device device, TestCase testCase, int attemptCount) {
        this.device = device;
        this.testCase = testCase;
        this.attemptCount = attemptCount;
    }

    public Device getDevice() {
        return device;
    }

    public TestCase getTestCase() {
        return testCase;
    }

    public void setDevice(Device device) {
        this.device = device;
    }

    public void setTestCase(TestCase testCase) {
        this.testCase = testCase;
    }

    public int getAttemptCount() {
        return this.attemptCount;
    }

    public PlannedTestRun withIncrementedAttemptCount() {
        return new PlannedTestRun(device, testCase, attemptCount + 1);
    }

    @Override
    public String toString() {
        return "PlannedTestRun(" + this.getDevice() + ";" + this.getTestCase() + ")";
    }
}
