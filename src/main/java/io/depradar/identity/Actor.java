package io.depradar.identity;

public record Actor(String userId, String name, String projectId) implements java.security.Principal {
    public boolean ci() { return projectId != null; }
    @Override public String getName() { return name; }
}
