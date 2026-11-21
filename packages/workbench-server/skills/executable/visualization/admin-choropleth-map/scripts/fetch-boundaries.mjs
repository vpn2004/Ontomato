import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const output = path.join(root, 'data', 'geojson', 'china-provinces.geojson');
const url = process.env.CHINA_PROVINCES_GEOJSON_URL || 'https://geojson.cn/api/china/1.6.3/100000.json';

const response = await fetch(url, { headers: { accept: 'application/geo+json, application/json' } });
if (!response.ok) throw new Error(`Unable to download province boundaries: HTTP ${response.status} from ${url}`);
const data = await response.json();
if (data?.type !== 'FeatureCollection' || !Array.isArray(data.features)) {
  throw new Error('The boundary source did not return a GeoJSON FeatureCollection.');
}
const polygonFeatures = data.features.filter(feature =>
  feature?.geometry && ['Polygon', 'MultiPolygon'].includes(feature.geometry.type)
);
if (!polygonFeatures.length) throw new Error('The downloaded GeoJSON has no Polygon or MultiPolygon features.');
const outputData = { ...data, features: polygonFeatures };
await mkdir(path.dirname(output), { recursive: true });
await writeFile(output, JSON.stringify(outputData), 'utf8');
console.log(`Saved ${polygonFeatures.length} polygon features to ${output}`);
console.log(`Source: ${url}`);
console.log('Coordinate system reported by source: GCJ-02 for GeoJSON.CN v1.6.3. Verify suitability before combining with other GIS datasets.');
