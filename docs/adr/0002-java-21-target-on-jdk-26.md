# 0002 — Target Java 21 while developing on JDK 26

**Status:** Accepted · 2026-08-12

## Context

The local development machine runs **JDK 26.0.1**. The project targets **Java 21
LTS**, chosen because it is the current LTS, is fully supported by Spring Boot
3.x, and is what new AWS services target.

Compiling on a newer JDK than the target introduces a real hazard: with the
legacy `-source`/`-target` flags, `javac` will happily accept APIs that do not
exist in the target version, producing bytecode that fails at runtime on the
actual target JDK.

## Decision

Target Java 21 using `maven.compiler.release` (`<release>21</release>`), **not**
`-source`/`-target`. Do not install a second JDK locally.

`--release` is strictly stronger: it compiles against the Java 21 API signatures,
so referencing an API introduced in 22–26 is a compile error rather than a
runtime surprise.

Additionally:

- CI pins its JDK explicitly rather than using "latest".
- `maven-enforcer-plugin` declares a minimum JDK so an unexpected toolchain
  fails the build loudly.

## Consequences

- No second JDK to install or manage locally.
- Java 22–26 language features and APIs are unavailable. This is intended.
- Local and CI bytecode are identical because both are pinned to release 21.
- `JAVA_HOME` should be set — currently unset on the development machine. Maven
  resolves a JDK via `PATH`, but Testcontainers, IDE run configurations, and
  some tooling expect `JAVA_HOME` explicitly.
- Moving to a newer LTS later means changing one property in the parent POM.
