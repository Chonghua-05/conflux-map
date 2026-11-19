import {readFile} from 'node:fs/promises';

const imports = {wasi_snapshot_preview1: new Proxy({}, {get: () => () => 0})};
const bytes = await readFile(process.argv[2]);
const {instance} = await WebAssembly.instantiate(bytes, imports);
const version = Number(process.argv[3]);
const seed = BigInt(process.argv[4]);
const lod = Number(process.argv[5]);
const dimension = Number(process.argv[6]);
const blockX = Number(process.argv[7] ?? 0);
const blockZ = Number(process.argv[8] ?? 0);
if (instance.exports.cfxWebInit(version, seed, dimension, 0) !== 0) {
  throw new Error('predictor initialization failed');
}
if (instance.exports.cfxWebGenerateTile(blockX, blockZ, lod, 1) !== 0) {
  throw new Error('tile prediction failed');
}
const cells = 258 * 258;
const biomes = new Int32Array(instance.exports.memory.buffer,
  instance.exports.cfxWebBiomeData(), cells);
const heights = new Int32Array(instance.exports.memory.buffer,
  instance.exports.cfxWebHeightData(), cells);
const surfaces = new Int32Array(instance.exports.memory.buffer,
  instance.exports.cfxWebSurfaceData(), cells);
const canopies = new Int32Array(instance.exports.memory.buffer,
  instance.exports.cfxWebCanopyData(), cells);
for (let i = 0; i < cells; i++) {
  console.log([heights[i], biomes[i], surfaces[i] & 3, canopies[i]].join(','));
}
