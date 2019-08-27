package com.jamosolutions.automator.domain;

public class ResponseStringWrapper {
    private String data;
    private boolean success;
    private String message;

    public ResponseStringWrapper() {
    }

    private ResponseStringWrapper(String data, boolean success, String message) {
        this.data = data;
        this.success = success;
        this.message = message;
    }

    public static ResponseStringWrapper wrapIt(String data, boolean success) {
        return new ResponseStringWrapper(data, success, null);
    }

    public static ResponseStringWrapper wrapIt(String data, boolean success, String message) {
        return new ResponseStringWrapper(data, success, message);
    }

    public static ResponseStringWrapper wrapIt(boolean success, String message) {
        return new ResponseStringWrapper(null, success, message);
    }

    public String getData() {
        return data;
    }

    public void setData(String data) {
        this.data = data;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    /**
     * Contains executionId when {@link #isSuccess()} is true.
     *
     * @return actual executionId of executed test run or undefined value (in case when {@link #isSuccess()} is not true)
     */
    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    @Override
    public String toString() {
        return "ResponseStringWrapper(" + this.success + ";" + this.data + ";" + this.message + ")";
    }
}