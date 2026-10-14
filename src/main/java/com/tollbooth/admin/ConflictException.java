package com.tollbooth.admin;

/** E.g. an app name that is already taken: becomes 409. */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
