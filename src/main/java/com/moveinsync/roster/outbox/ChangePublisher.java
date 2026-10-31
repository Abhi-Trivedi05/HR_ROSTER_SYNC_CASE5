package com.moveinsync.roster.outbox;

public interface ChangePublisher { void publish(ChangeMessage message) throws Exception; }
