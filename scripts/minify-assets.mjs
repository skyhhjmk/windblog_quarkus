import { execFileSync, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

const projectRoot = process.cwd();
const targetClassesDir = path.join(projectRoot, 'target', 'classes');
const assetsRoot = path.join(targetClassesDir, 'META-INF', 'resources', 'assets');
const manifestPath = path.join(targetClassesDir, 'windblog-assets.properties');

function findEsbuild() {
    if (process.env.ESBUILD_BIN && fs.existsSync(process.env.ESBUILD_BIN)) {
        return process.env.ESBUILD_BIN;
    }

    const candidates = [
        'esbuild',
        path.join(projectRoot, 'node_modules', '.bin', 'esbuild')
    ];

    try {
        const npmBin = execFileSync('npm', ['prefix', '-g'], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim();
        if (npmBin) {
            candidates.push(path.join(npmBin, 'bin', 'esbuild'));
        }
    } catch {
        // Ignore npm prefix lookup failure
    }

    for (const candidate of candidates) {
        const probe = spawnSync(candidate, ['--version'], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] });
        if (probe.status === 0) {
            return candidate;
        }
    }
    return null;
}

function collectFiles(dir, ext) {
    if (!fs.existsSync(dir)) {
        return [];
    }
    return fs.readdirSync(dir, { withFileTypes: true })
        .filter((entry) => entry.isFile())
        .map((entry) => entry.name)
        .filter((name) => name.endsWith(ext) && !name.endsWith(`.min${ext}`))
        .map((name) => path.join(dir, name));
}

function writeProps(minified) {
    fs.mkdirSync(targetClassesDir, { recursive: true });
    fs.writeFileSync(manifestPath, `minified=${minified ? 'true' : 'false'}\n`, 'utf8');
}

function runEsbuildBatch(esbuildBin, files, outDir, ext) {
    if (files.length === 0) {
        return 0;
    }
    execFileSync(
        esbuildBin,
        [
            ...files,
            '--minify',
            '--charset=utf8',
            `--outdir=${outDir}`,
            `--out-extension:${ext}=.min${ext}`
        ],
        { stdio: 'inherit' }
    );
    return files.length;
}

function main() {
    if (!fs.existsSync(assetsRoot)) {
        console.log('[minify-assets] target/classes assets directory not found, skipping.');
        writeProps(false);
        return;
    }

    const esbuildBin = findEsbuild();
    if (!esbuildBin) {
        console.log('[minify-assets] esbuild not found; skipping minification (unminified assets will be served).');
        writeProps(false);
        return;
    }

    const jsDir = path.join(assetsRoot, 'js');
    const cssDir = path.join(assetsRoot, 'css');
    const jsFiles = collectFiles(jsDir, '.js');
    const cssFiles = collectFiles(cssDir, '.css');

    const start = Date.now();
    const jsCount = runEsbuildBatch(esbuildBin, jsFiles, jsDir, '.js');
    const cssCount = runEsbuildBatch(esbuildBin, cssFiles, cssDir, '.css');
    const elapsed = Date.now() - start;

    writeProps(true);
    console.log(`[minify-assets] Minified ${jsCount} JS and ${cssCount} CSS files in ${elapsed}ms using ${esbuildBin}.`);
}

main();
