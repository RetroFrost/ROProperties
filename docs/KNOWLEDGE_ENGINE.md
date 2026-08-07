# Property knowledge engine

ROProperties intentionally separates **property meaning** from **value meaning**.

The engine resolves a property in three layers:

1. **Curated exact definitions** for Android properties where semantics and important values are known.
2. **Pattern definitions** for families such as product identity, partition fingerprints, security-patch levels and ABI lists.
3. **Honest inference fallback** for everything else. The fallback identifies the likely namespace/origin, humanises the property topic, classifies common value shapes and explicitly labels the result `Inferred`.

The fallback exists because Android does not have one public universal dictionary containing every `ro.*` property. Device makers, SoC vendors, ROMs and individual components can define their own properties.

## Confidence

- **Documented** — semantics are sufficiently established to present as an Android/platform definition.
- **Known** — behaviour is known from an implementation/vendor family but is not treated as universal AOSP semantics.
- **Inferred** — ROProperties can explain the namespace/value shape but cannot authoritatively claim the exact consumer semantics.
- **Unknown** — reserved for cases where even a useful inference cannot be made.

## Contributions

When adding a definition, prefer the exact property name and include:

- property meaning;
- value mappings or a value interpreter;
- origin;
- risk;
- likely consumers;
- notes when changing the string does not change the underlying capability/security state.

Never turn an OEM guess into a `Documented` definition without evidence.
