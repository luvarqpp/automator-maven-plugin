package com.jamosolutions.automator.help;

import com.jamosolutions.automator.domain.Device;
import com.jamosolutions.automator.domain.TestCase;

public class FutureExecution {
    private Device device;
    private TestCase testCase;

    public FutureExecution(Device device, TestCase testCase) {
        this.device = device;
        this.testCase = testCase;
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
}
