package com.tollbooth.admin;

/** An app or key id that doesn't exist: becomes 404. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
