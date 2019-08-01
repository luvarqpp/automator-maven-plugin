package com.jamosolutions.automator.help;

import com.jamosolutions.automator.domain.Device;
import com.jamosolutions.automator.domain.TestCase;

public class Execution extends FutureExecution {
    private String executionId;
    private long startTimeMillis;

    public Execution(String executionId, Device device, TestCase testCase) {
        super(device, testCase);
        this.executionId = executionId;
        this.startTimeMillis = System.currentTimeMillis();
    }

    public String getExecutionId() {
        return executionId;
    }

    public void setExecutionId(String executionId) {
        this.executionId = executionId;
    }

    public long getStartTimeMillis() {
        return this.startTimeMillis;
    }
}
