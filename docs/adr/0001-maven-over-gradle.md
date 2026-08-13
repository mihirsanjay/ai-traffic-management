# 0001 — Maven over Gradle

**Status:** Accepted · 2026-08-12

## Context

The project is a multi-module Java/Spring Boot monorepo and needs a build tool.
Maven and Gradle are both viable.

Gradle offers faster incremental builds, a Kotlin DSL with real type safety, and
better multi-module ergonomics on large codebases. Maven offers a declarative
XML model, near-universal familiarity in enterprise Java, and first-class
treatment in Spring documentation.

## Decision

Use **Maven**, with a parent POM providing dependency and plugin management.

The deciding factor is ecosystem alignment rather than raw capability. Enterprise
Java shops — including AWS teams — predominantly run Maven; Spring Boot's own
documentation and the majority of available examples assume it. For a project
whose explicit purpose includes understanding why real engineering organizations
choose what they choose, matching the dominant convention is worth more than
Gradle's build-speed advantage on a codebase this size.

## Consequences

- Build times will be slower than Gradle's on incremental builds. At this
  project's scale, that difference is not material.
- The XML is verbose; `<dependencyManagement>` in the parent POM keeps version
  declarations in one place.
- Every developer and CI system can be assumed to understand the build without
  explanation.
- Plugin configuration for Spotless, Checkstyle, SpotBugs, JaCoCo, and Enforcer
  is well documented for Maven.
