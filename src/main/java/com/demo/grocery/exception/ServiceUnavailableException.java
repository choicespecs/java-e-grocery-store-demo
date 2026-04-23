package com.demo.grocery.exception;

public class ServiceUnavailableException extends RuntimeException {

    private final String compensationNote;

    public ServiceUnavailableException(String message) {
        super(message);
        this.compensationNote = null;
    }

    public ServiceUnavailableException(String message, String compensationNote) {
        super(message);
        this.compensationNote = compensationNote;
    }

    public String getCompensationNote() { return compensationNote; }
    public boolean hasCompensationNote() { return compensationNote != null; }
}
