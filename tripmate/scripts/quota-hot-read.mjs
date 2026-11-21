// Run from the repository after mvn -o "-Dtest=QuotaActivity*Test" -f tripmate/pom.xml test.
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import http from 'node:http';
import readline from 'node:readline';
import { performance } from 'node:perf_hooks';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';

const project = fileURLToPath(new URL('../', import.meta.url));
const output = path.resolve(process.argv[2] || path.join(project, 'target/quota-benchmark'));
fs.mkdirSync(output, { recursive: true });
const report = fs.readFileSync(path.join(project, 'target/surefire-reports/TEST-com.ecnu.tripmate.quota.QuotaActivityCacheTest.xml'), 'utf8');
const cp = report.match(/name="java.class.path" value="([^"]+)"/)[1].replaceAll('&amp;', '&');
const argFile = path.join(project, 'target/quota-benchmark.args');
fs.writeFileSync(argFile, '-cp\n"' + cp.replaceAll('\\', '/') + '"\n');
const nodes = [];
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));

async function startNode(local, countGets = false) {
    const proc = spawn('java', ['-Xms256m', '-Xmx512m', '@' + argFile, 'com.ecnu.tripmate.quota.QuotaBenchmarkNode', String(local), countGets ? 'metrics' : 'normal'],
        { cwd: project, windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] });
    const lines = readline.createInterface({ input: proc.stdout });
    const replies = [];
    let wait, failure;
    const errors = [];
    const reads = [];
    const counts = {};
    const diagnose = text => {
        for (const m of text.matchAll(/(?:[\w.]+(?:Exception|Error))|SQLState[=: ]+[\w-]+/g)) errors.push(m[0]);
    };
    proc.stderr.on('data', buf => diagnose(buf.toString()));
    lines.on('line', line => {
        if (line.startsWith('CACHECOUNTS ')) {
            const [configGET, stockGET, hits, misses] = line.slice('CACHECOUNTS '.length).split(' ').map(Number);
            Object.assign(counts, {configGET, stockGET, hits, misses});
            return;
        }
        if (line.startsWith('GETCOUNTS ')) {
            const [, local, batch, configGET, stockGET] = line.split(' ');
            reads.push({localEnabled: local === 'true', batch: Number(batch), configGET: Number(configGET), stockGET: Number(stockGET)});
            return;
        }
        if (!line.startsWith('BENCH ')) { diagnose(line); return; }
        const value = line.split(' ');
        if (wait) { const w = wait; wait = null; w.resolve(value); }
        else replies.push(value);
    });
    const exited = new Promise(resolve => proc.once('close', code => {
        failure = new Error('node exited ' + code + ' (' + [...new Set(errors)].join(', ') + ')');
        if (wait) wait.reject(failure);
        resolve(code);
    }));
    function next() {
        if (replies.length) return Promise.resolve(replies.shift());
        if (failure) return Promise.reject(failure);
        return new Promise((resolve, reject) => { wait = {resolve, reject}; });
    }
    const node = { proc, exited, reads, counts, async command(cmd) {
        const response = next(); proc.stdin.write(cmd + '\n'); const [,ok,id] = await response;
        assert.equal(ok, 'OK'); return Number(id);
    }};
    nodes.push(node);
    const [,ready,port] = await next();
    assert.equal(ready, 'READY'); node.port = Number(port);
    return node;
}
async function stop(node) {
    if (!node.proc.stdin.destroyed && !node.proc.stdin.writableEnded) node.proc.stdin.end('exit\n');
    assert.equal(await node.exited, 0);
}
const agent = new http.Agent({ keepAlive: true, maxSockets: 100, maxFreeSockets: 100 });
function read(port, id) {
    return new Promise((resolve, reject) => {
        const req = http.get({ hostname: '127.0.0.1', port, path: '/api/quota/activities/' + id, agent }, res => {
            const chunks = [];
            res.on('data', part => chunks.push(part));
            res.on('error', reject);
            res.on('end', () => {
                try {
                    assert.equal(res.statusCode, 200);
                    const body = JSON.parse(Buffer.concat(chunks));
                    assert.equal(body.code, 0);
                    assert.equal(body.data.config.id, id);
                    resolve(body.data);
                } catch (e) { reject(e); }
            });
        });
        req.setTimeout(5000, () => req.destroy(new Error('timeout')));
        req.on('error', reject);
    });
}
async function phase(port, id, seconds, version = 3, stock = 97) {
    const samples = [], errors = {};
    const started = performance.now(), until = started + seconds * 1000;
    let failures = 0;
    async function worker() {
        while (performance.now() < until && failures < 100) {
            const begin = performance.now();
            try {
                const detail = await read(port, id);
                assert.equal(detail.config.configVersion, version);
                assert.equal(detail.stockAvailable, true);
                assert.equal(detail.remainingStock, stock);
                samples.push(Math.round((performance.now() - begin) * 1000));
            } catch (e) {
                failures++;
                const kind = e.code || e.name;
                errors[kind] = (errors[kind] || 0) + 1;
            }
        }
    }
    await Promise.all(Array.from({length:100}, worker));
    const elapsedSeconds = (performance.now() - started) / 1000;
    if (failures >= 100 || !samples.length) throw new Error('load phase failed ' + JSON.stringify(errors));
    samples.sort((a,b) => a-b);
    const percentile = p => samples[Math.ceil(samples.length * p) - 1] / 1000;
    const histogram = {};
    for (const us of samples) {
        const bin = Math.ceil(us / 100);
        histogram[bin] = (histogram[bin] || 0) + 1;
    }
    return {elapsedSeconds, successes: samples.length, failures, errors,
        qps: samples.length / elapsedSeconds, p50: percentile(.50), p95: percentile(.95),
        p99: percentile(.99), histogram100us: histogram};
}

async function benchmark() {
    const primary = await startNode(true);
    const peer = await startNode(true);
    const id = await primary.command('setup');
    console.log('Temporary activity created, concurrent publish and stock preservation verified', id);
    assert.equal((await read(peer.port,id)).config.configVersion, 2);
    const before = performance.now();
    await primary.command('update');
    let detail;
    do {
        detail = await read(peer.port,id);
        if (detail.config.configVersion === 3) break;
        await delay(10);
    } while (performance.now() - before < 4000);
    assert.equal(detail.config.configVersion, 3);
    const observedMs = performance.now() - before;
    await primary.command('rollback');
    assert.equal((await read(peer.port,id)).config.configVersion, 3);
    await primary.command('recover');
    console.log('Peer update observed in', observedMs.toFixed(1),'ms; rollback and stock reconciliation verified');
    await stop(peer);
    const baseline = await startNode(false);
    const result = {startedAt:new Date().toISOString(), activityId:id, observedMs, concurrency:100,
        warmupSeconds:10, measureSeconds:30, hardware:os.cpus()[0].model,
        logicalCPUs:os.cpus().length, memoryGiB:os.totalmem()/2**30, node:process.version, rounds:[]};
    for (const [index,mode] of ['Redis','Caffeine+Redis','Caffeine+Redis','Redis','Redis','Caffeine+Redis'].entries()) {
        const port = mode === 'Redis' ? baseline.port : primary.port;
        const warm = await phase(port,id,result.warmupSeconds);
        assert.equal(warm.failures, 0);
        const run = await phase(port,id,result.measureSeconds);
        result.rounds.push({round:index+1, mode, ...run});
        fs.writeFileSync(path.join(output,'quota-hot-read.json'),JSON.stringify(result,null,2));
        console.log('Round',index+1,mode,'requests',run.successes,'errors',run.failures,'QPS',run.qps.toFixed(1),'P95ms',run.p95);
    }
    await primary.command('clean');
    result.cleaned = true;
    fs.writeFileSync(path.join(output,'quota-hot-read.json'),JSON.stringify(result,null,2));
    console.log('Test rows and keys removed.');
}

async function measureCacheMetrics() {
    const primary = await startNode(true, true);
    const baseline = await startNode(false, true);
    const id = await primary.command('setup');
    const result = {startedAt:new Date().toISOString(), activityId:id, concurrency:100,
        warmupSeconds:10, measureSeconds:65, rounds:[]};
    for (const [mode, node] of [['Redis', baseline], ['Caffeine+Redis', primary]]) {
        const warm = await phase(node.port, id, result.warmupSeconds, 2, 98);
        assert.equal(warm.failures, 0);
        await node.command('measure ' + id);
        const run = await phase(node.port, id, result.measureSeconds, 2, 98);
        await node.command('counts');
        const {configGET, stockGET, hits, misses} = node.counts;
        assert.equal(run.failures, 0);
        assert.equal(stockGET, run.successes);
        assert.equal(hits + misses, node === primary ? run.successes : 0);
        if (node === baseline) assert.equal(configGET, run.successes);
        const sample = {mode, successes:run.successes, failures:run.failures, elapsedSeconds:run.elapsedSeconds,
            configGET, stockGET, hits, misses, hitRate: hits + misses ? hits / (hits + misses) : null,
            getsPerRequest:(configGET + stockGET) / run.successes, configGetsPerRequest:configGET / run.successes};
        result.rounds.push(sample);
        console.log('Measured', sample);
    }
    const [a,b] = result.rounds;
    result.getReduction = 1 - b.getsPerRequest / a.getsPerRequest;
    result.configGetReduction = 1 - b.configGetsPerRequest / a.configGetsPerRequest;
    await primary.command('clean');
    result.cleaned = true;
    fs.writeFileSync(path.join(output, 'quota-cache-metrics.json'), JSON.stringify(result, null, 2));
}

try {
    if (process.argv.includes('--cache-metrics')) {
        await measureCacheMetrics();
    } else if (process.argv.includes('--commands-only')) {
        const primary = await startNode(true);
        const id = await primary.command('setup');
        await primary.command('reads');
        await primary.command('clean');
        assert.equal(primary.reads.length, 4);
        fs.writeFileSync(path.join(output, 'quota-get-counts.json'), JSON.stringify({
            activityId: id, concurrency: 10, samples: primary.reads, cleaned: true
        }, null, 2));
        console.log('Verified GET counts', primary.reads);
    } else {
        await benchmark();
    }
} finally {
    agent.destroy();
    for (const node of nodes.reverse()) {
        if (node.proc.exitCode === null && !node.proc.stdin.writableEnded) await stop(node);
    }
}

