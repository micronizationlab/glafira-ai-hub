# Windows Hub binding persistence migration

## Why direct Codable persistence is not supported

`BridgeHubBinding` intentionally does not conform to `Codable`. A generic `JSONDecoder` cannot enforce the persistence contract's byte limit, duplicate-key rejection, nesting bound, exact schema, endpoint canonicalization and field validation before constructing the trusted binding.

Do not restore a synthesized `Codable` conformance for this type. It would create a second, less restrictive decoding path beside the validated persistence boundary.

## Replace legacy JSONDecoder / JSONEncoder calls

Before:

```swift
let binding = try JSONDecoder().decode(BridgeHubBinding.self, from: storedData)
let storedData = try JSONEncoder().encode(binding)
```

After:

```swift
let binding = try BridgeHubBindingPersistence.decode(storedData)
let storedData = try BridgeHubBindingPersistence.encode(binding)
```

Use the same bounded persistence API for both storage and restore. Do not decode persisted Hub bindings through a generic decoder, even if the JSON was previously emitted by this application.

## If an application needs a Codable transport DTO

Keep that DTO separate from `BridgeHubBinding`. Treat its fields as untrusted input and construct a `BridgeHubBinding` only through the validated initializer or `BridgeHubBindingPersistence.decode`. Never pass a DTO directly to the credential store or use it as a trust anchor.

The migration is intentionally explicit: call sites constrained to `Codable` must move to the bounded persistence API rather than silently retaining an unbounded decode route.
