package com.gianlu.aria2android.NetIO;

public class StatusCodeException extends Exception {
    public StatusCodeException(int code, String message) {
        super(code + ": " + message);
    }
}