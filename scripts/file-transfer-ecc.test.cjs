const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

const wasmPath = path.resolve(__dirname, '../src/main/resources/META-INF/resources/assets/vendor/file-transfer-wasm/file_transfer_ecc.wasm');
const wasmBytes = fs.readFileSync(wasmPath);

async function loadWasm() {
    const { instance } = await WebAssembly.instantiate(wasmBytes, {});
    const exp = instance.exports;
    const mem = () => new Uint8Array(exp.memory.buffer);
    return { exp, mem };
}

test('WASM CRC32 matches standard IEEE 802.3 vector', async () => {
    const { exp, mem } = await loadWasm();
    const input = Buffer.from('123456789', 'utf8');
    const ptr = exp.alloc(input.length);
    mem().set(input, ptr);
    const crc = exp.crc32(ptr, input.length) >>> 0;
    exp.dealloc(ptr, input.length);
    assert.equal(crc, 0xCBF43926);
});

test('WASM Reed-Solomon (k=16, m=3) encodes and recovers 3 lost shards', async () => {
    const { exp, mem } = await loadWasm();
    const k = 16;
    const m = 3;
    const shardLen = 1024;
    const original = crypto.randomBytes(k * shardLen);

    const dataPtr = exp.alloc(k * shardLen);
    const parityPtr = exp.alloc(m * shardLen);
    mem().set(original, dataPtr);
    assert.equal(exp.rs_encode(dataPtr, k, m, shardLen, parityPtr), 0);
    const parity = mem().slice(parityPtr, parityPtr + m * shardLen);
    exp.dealloc(dataPtr, k * shardLen);
    exp.dealloc(parityPtr, m * shardLen);

    const allShards = new Uint8Array((k + m) * shardLen);
    allShards.set(original, 0);
    allShards.set(parity, k * shardLen);

    const present = new Uint8Array(k + m).fill(1);
    // Drop 3 data shards: 1, 7, 14
    for (const idx of [1, 7, 14]) {
        present[idx] = 0;
        allShards.fill(0, idx * shardLen, (idx + 1) * shardLen);
    }

    const shardsPtr = exp.alloc((k + m) * shardLen);
    const presentPtr = exp.alloc(k + m);
    mem().set(allShards, shardsPtr);
    mem().set(present, presentPtr);
    const recovered = exp.rs_reconstruct(shardsPtr, presentPtr, k, m, shardLen);
    const outData = mem().slice(shardsPtr, shardsPtr + k * shardLen);
    exp.dealloc(shardsPtr, (k + m) * shardLen);
    exp.dealloc(presentPtr, k + m);

    assert.equal(recovered, 3);
    assert.deepEqual(Buffer.from(outData), original);
});

test('WASM Reed-Solomon (k=12, m=4) returns -1 when > m shards are lost', async () => {
    const { exp, mem } = await loadWasm();
    const k = 12;
    const m = 4;
    const shardLen = 256;
    const allShards = new Uint8Array((k + m) * shardLen);
    const present = new Uint8Array(k + m).fill(1);
    for (const idx of [0, 2, 4, 6, 8]) present[idx] = 0;

    const shardsPtr = exp.alloc((k + m) * shardLen);
    const presentPtr = exp.alloc(k + m);
    mem().set(allShards, shardsPtr);
    mem().set(present, presentPtr);
    const rc = exp.rs_reconstruct(shardsPtr, presentPtr, k, m, shardLen);
    exp.dealloc(shardsPtr, (k + m) * shardLen);
    exp.dealloc(presentPtr, k + m);

    assert.equal(rc, -1);
});
