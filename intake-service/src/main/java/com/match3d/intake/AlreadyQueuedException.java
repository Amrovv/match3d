package com.match3d.intake;

/** A member is queued in another entry. Thrown inside a join so its transaction rolls back. */
public class AlreadyQueuedException extends RuntimeException {

    public AlreadyQueuedException() {
        super("a member is already queued");
    }
}
