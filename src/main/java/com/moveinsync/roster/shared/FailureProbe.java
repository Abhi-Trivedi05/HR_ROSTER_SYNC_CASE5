package com.moveinsync.roster.shared;

/** Test seam only: production has no request/configuration path to activate failures. */
@FunctionalInterface
public interface FailureProbe { void at(String point); }
