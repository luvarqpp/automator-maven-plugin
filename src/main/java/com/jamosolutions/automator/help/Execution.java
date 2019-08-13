package com.jamosolutions.automator.help;

import com.jamosolutions.automator.domain.Device;
import com.jamosolutions.automator.domain.TestCase;

public class Execution extends FutureExecution {
    private String executionId;
    private long startTimeMillis;
    private long requestStartTime;
    private int errorsWhileGettingReport = 0;

    public Execution(String executionId, Device device, TestCase testCase, long requestStartTime) {
        super(device, testCase);
        this.executionId = executionId;
        this.requestStartTime = requestStartTime;
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

    public long getRequestStartTime() {
        return requestStartTime;
    }

    public void errorGettingReport(Exception ex) {
        this.errorsWhileGettingReport++;
    }

    public int getErrorsWhileGettingReport() {
        return this.errorsWhileGettingReport;
    }
}
