package com.harishdarko.caselens.triage;

import java.util.UUID;

/** Finds the industry pack of a workspace. */
@FunctionalInterface
public interface PackResolver {
    PackRules forWorkspace(UUID workspaceId);
}
