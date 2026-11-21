# China province boundaries

Expected cached/bundled file: `china-provinces.geojson`.

This package uses GeoJSON.CN's China administrative boundary dataset, version 1.6.3, as its default source:

- Dataset page: https://geojson.cn/data/atlas/china
- Default endpoint: https://geojson.cn/api/china/1.6.3/100000.json
- Source version's coordinate system: GCJ-02 (per the source documentation).
- Source documentation reports version 1.6.3 updated on 2025-12-14.

The boundary data file is included in this archive. To refresh it, run from the skill root:

```bash
node scripts/fetch-boundaries.mjs
```

The skill also attempts to load the same source automatically if the local file is absent, then caches the downloaded data at this path when filesystem writes are allowed. If both local access and network access are unavailable, pass `input.geojson` explicitly.

Please review the data provider's terms and applicable map/data regulations before redistribution or commercial use. The boundaries are intended for statistical visualization, not legal boundary determination, navigation, or surveying.
