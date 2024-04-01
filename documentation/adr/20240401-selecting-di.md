# Selecting DI

- Status: accepted
- Deciders: vladar107
- Date: 2024-04-01
- Tags: tools, DI

## Context and Problem Statement

We need to select a DI framework for our project.

## Decision Drivers

- Ease of use
- Performant at runtime
- Community support

## Considered Options

- [Dagger](https://dagger.dev/)
- [Koin](https://insert-koin.io/)
- [Kodein](https://kosi-libs.org/kodein/7.21/index.html)

## Decision Outcome

Chosen option: "Kodein", because it is easy to use, has a large community, and allows multi-module DI.

## Pros and Cons of the Options <!-- optional -->

### Dagger

Dagger is a compile-time DI framework that generates code for you. It is fast and has a large community.

- Good, because compile-time DI is performant
- Good, because large community
- Bad, because compile-time DI is harder to use
- Bad, because of Java code generation worsen compile time and readability

### Koin

Koin is a runtime DI framework that is easy to use and has a large community.

- Good, because easy to use
- Good, because large community
- Good, because of Kotlin DSL
- Bad, because runtime DI is slower
- Bad, because own wrapper for ApplicationRun

### Kodein

Kodein is a runtime DI framework that is easy to use and has a large community.

- Good, because easy to use
- Good, because large community
- Good, because of Kotlin DSL
- Good, because allows multi-module DI
- Bad, because runtime DI is slower
