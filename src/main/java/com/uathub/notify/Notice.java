package com.uathub.notify;

import java.util.Set;

/**
 * Something people should hear about. Published inside a transaction and delivered after it commits,
 * so a rolled-back change never sends a message.
 *
 * @param userIds   who gets an email (people who switched email off, or have no address, are skipped)
 * @param teams     also post to the project's Teams channel
 * @param path      app path the message links to, e.g. /feedback/42
 */
public record Notice(Long projectId, Set<Long> userIds, String subject, String body, String path, boolean teams) {
}
