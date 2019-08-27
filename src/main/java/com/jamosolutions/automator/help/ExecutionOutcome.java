package com.jamosolutions.automator.help;

// TODO rename to TestRunOutcome?
public enum ExecutionOutcome {
    /**
     * Device has executed test and all asserts has been passed as correct.
     */
    SUCCESS,
    /**
     * Device has executed test, but at least one assert has failed. This should mean that SUT (system under test)
     * have some defect in it.
     */
    FAILURE,
    /**
     * Error while delegating test execution to jamo automator service. Common type of "message" in this outcome are:
     * <ul>
     *     <li>TODO device offline</li>
     *     <li>TODO test does not exist</li>
     * </ul>
     */
    EXECERR,
    /**
     * Test has been executed, but report for test outcome has not been found within timeout defined in input for this
     * plugin. It is possible that device has gone offline during test execution, or test report creation has somehow
     * failed.
     *
     * When test execution finishes in this state, you can try open report url
     * (based on {@link TestRun#getExecutionId()}) later and see its outcome possibly.
     */
    TIMEOUT
}
