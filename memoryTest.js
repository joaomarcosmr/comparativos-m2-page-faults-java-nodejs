const fs = require('fs');
const path = require('path');
const { performance } = require('perf_hooks');

const CONFIG_PATH = path.join(__dirname, '..', 'config', 'memory-scenarios.json');
const REPORT_DIR = path.join(__dirname, '..', 'reports', 'memory');

function loadScenarioConfig() {
    try {
        const content = fs.readFileSync(CONFIG_PATH, 'utf8');
        return JSON.parse(content);
    } catch (error) {
        console.warn(`Não foi possível carregar ${CONFIG_PATH}: ${error.message}`);
        return [];
    }
}

function parseCliArgs(argv) {
    const args = {};
    for (let i = 2; i < argv.length; i++) {
        const token = argv[i];
        if (token.startsWith('--')) {
            const [key, value] = token.includes('=')
                ? token.split('=')
                : [token, argv[i + 1]];
            const normalizedKey = key.replace(/^--/, '');
            if (value && !value.startsWith('--') && token === key) {
                args[normalizedKey] = value;
                i++;
            } else if (token.includes('=')) {
                args[normalizedKey] = value;
            } else {
                args[normalizedKey] = true;
            }
        }
    }
    return args;
}

function resolveScenarios(cliArgs) {
    const config = loadScenarioConfig();
    if (!config.length) {
        throw new Error('Nenhum cenário configurado em config/memory-scenarios.json');
    }

    if (cliArgs.sizes) {
        const iterations = cliArgs.iterations ? Number(cliArgs.iterations) : 50;
        const requestedSizes = cliArgs.sizes.split(',').map(value => Number(value.trim())).filter(Boolean);
        return requestedSizes.map(sizeMb => ({
            id: `ad-hoc-${sizeMb}mb`,
            sizeMb,
            iterations,
        }));
    }

    const scenarioIds = cliArgs.scenarios ? cliArgs.scenarios.split(',').map(item => item.trim()) : [];
    if (!scenarioIds.length) {
        return config;
    }

    const selected = config.filter(scenario => scenarioIds.includes(scenario.id));
    const missing = scenarioIds.filter(id => !selected.find(item => item.id === id));

    if (missing.length) {
        throw new Error(`Cenários não encontrados: ${missing.join(', ')}`);
    }

    return selected;
}

function bytesFromMb(sizeMb) {
    return sizeMb * 1024 * 1024;
}

function nanosToSeconds(nanos) {
    return nanos / 1e9;
}

function measureAllocation(sizeBytes, iterations) {
    const start = performance.now();
    for (let iteration = 0; iteration < iterations; iteration++) {
        const buffer = Buffer.alloc(sizeBytes);
        buffer[0] = (buffer[0] + iteration) & 0xff;
    }
    const end = performance.now();
    return (end - start) / 1000;
}

function measureAllocateAndFree(sizeBytes, iterations) {
    const start = performance.now();
    let accumulator = 0;
    for (let iteration = 0; iteration < iterations; iteration++) {
        const buffer = Buffer.alloc(sizeBytes);
        accumulator += buffer[sizeBytes - 1];
    }
    // Consome valor acumulado para evitar eliminação pelo motor JS.
    if (accumulator === Number.MIN_SAFE_INTEGER) {
        console.log('accumulator sentinel', accumulator);
    }
    const end = performance.now();
    return (end - start) / 1000;
}

function measureWrites(sizeBytes, iterations) {
    const buffer = Buffer.alloc(sizeBytes);
    const start = performance.now();
    for (let iteration = 0; iteration < iterations; iteration++) {
        buffer.fill(iteration & 0xff);
    }
    const end = performance.now();
    return (end - start) / 1000;
}

function measureReads(sizeBytes, iterations) {
    const buffer = Buffer.alloc(sizeBytes, 0xaa);
    let accumulator = 0;
    const start = performance.now();
    for (let iteration = 0; iteration < iterations; iteration++) {
        for (let index = 0; index < buffer.length; index += 4096) {
            accumulator += buffer[index];
        }
    }
    if (accumulator === Number.MAX_SAFE_INTEGER) {
        console.log('accumulator sentinel', accumulator);
    }
    const end = performance.now();
    return (end - start) / 1000;
}

function captureResourceUsage() {
    if (typeof process.resourceUsage !== 'function') {
        return null;
    }
    return process.resourceUsage();
}

function computePageFaultMetrics(startUsage, endUsage) {
    if (!startUsage || !endUsage) {
        return {
            pageFaultsMinor: null,
            pageFaultsMajor: null,
        };
    }
    const pageFaultsMinor = Math.max(
        0,
        (endUsage.minorPageFault ?? 0) - (startUsage.minorPageFault ?? 0)
    );
    const pageFaultsMajor = Math.max(
        0,
        (endUsage.majorPageFault ?? 0) - (startUsage.majorPageFault ?? 0)
    );
    return {
        pageFaultsMinor,
        pageFaultsMajor,
    };
}

function runScenario(scenario) {
    const sizeBytes = bytesFromMb(scenario.sizeMb);
    const iterations = scenario.iterations;

    console.log(`\nExecutando cenário ${scenario.id} (${scenario.sizeMb} MB x ${iterations} iterações)`);

    const resourceUsageStart = captureResourceUsage();

    const allocationSeconds = measureAllocation(sizeBytes, iterations);
    const allocateAndFreeSeconds = measureAllocateAndFree(sizeBytes, iterations);
    const writesSeconds = measureWrites(sizeBytes, iterations);
    const readsSeconds = measureReads(sizeBytes, iterations);

    const resourceUsageEnd = captureResourceUsage();
    const {
        pageFaultsMinor,
        pageFaultsMajor,
    } = computePageFaultMetrics(resourceUsageStart, resourceUsageEnd);

    const result = {
        scenarioId: scenario.id,
        sizeMb: scenario.sizeMb,
        iterations,
        metrics: {
            allocationSeconds,
            allocateAndFreeSeconds,
            writesSeconds,
            readsSeconds,
            pageFaultsMinor,
            pageFaultsMajor,
        },
        timestamp: new Date().toISOString(),
        nodeVersion: process.version,
    };

    console.table({
        'Alocação': `${allocationSeconds.toFixed(4)} s`,
        'Aloca + Libera': `${allocateAndFreeSeconds.toFixed(4)} s`,
        'Escrita': `${writesSeconds.toFixed(4)} s`,
        'Leitura': `${readsSeconds.toFixed(4)} s`,
        'Page faults (minor)': pageFaultsMinor ?? 'n/d',
        'Page faults (major)': pageFaultsMajor ?? 'n/d',
    });

    return result;
}

function ensureReportDir() {
    fs.mkdirSync(REPORT_DIR, { recursive: true });
}

function persistResults(results, customOutput) {
    ensureReportDir();
    const fileName = customOutput
        ? path.isAbsolute(customOutput)
            ? customOutput
            : path.join(REPORT_DIR, customOutput)
        : path.join(
            REPORT_DIR,
            `${new Date().toISOString().replace(/[:.]/g, '-')}-memory-test.json`
        );
    fs.writeFileSync(fileName, JSON.stringify(results, null, 2));
    console.log(`Resultados gravados em ${fileName}`);
}

function main() {
    const cliArgs = parseCliArgs(process.argv);
    console.log({ cliArgs });
    try {
        const scenarios = resolveScenarios(cliArgs);
        const results = scenarios.map(runScenario);
        persistResults(results, cliArgs.output);
    } catch (error) {
        console.error(`Erro: ${error.message}`);
        process.exitCode = 1;
    }
}

if (require.main === module) {
    main();
}


/**
 *
 * • Esses tempos parecem vir de um teste sintético de memória/IO. A leitura rápida:
 *
 *   - allocationSeconds mede quanto tempo foi gasto apenas alocando memória.
 *   - allocateAndFreeSeconds inclui o ciclo alocar + liberar.
 *   - writesSeconds e readsSeconds cobrem o tempo em escrever e ler blocos de memória/discos.
 *
 *   Quando você aumenta o número de processos concorrentes:
 *
 *   - allocationSeconds tende a subir porque o alocador briga por locks internos, fica mais tempo em syscalls de memória e pode sofrer pressão de RAM (page faults, swap).
 *   - allocateAndFreeSeconds cresce ainda mais que allocationSeconds, pois a liberação passa pelo coletor/handoff ao kernel; quanto mais processos, maior a fila de liberações e possíveis
 *     falhas de cache, fragmentação, TLB shootdowns.
 *   - writesSeconds sofre bastante se os processos escrevem no mesmo disco/memória compartilhada: o scheduler interdita o IO, a cache do SO fica saturada, e eventualmente o kernel passa
 *     a flushar mais para swap/disk; o tempo médio cresce quase linear a partir de um certo ponto.
 *   - readsSeconds aumenta por competição por cache e buffers. Processos extras invalidam cache L1/L2/L3 com mais frequência e o kernel precisa carregar páginas de volta do disco/swap,
 *     elevando a latência.
 *
 *   Já ao reduzir processos:
 *
 *   - Todos os tempos despencam por causa da menor contenção: o alocador tem caminho livre, o kernel atende IO com menos fila, a cache de CPU passa a reter mais dados, e as leituras
 *     evitam page faults.
 *
 *   O ideal é monitorar essas métricas à medida que você escala processos/threads. Se writesSeconds ou readsSeconds crescerem desproporcionalmente (por exemplo, dobrar quando só
 *   aumentou 20% os processos), é um sinal de saturação de IO ou de pressão de memória; se allocationSeconds explodir, você provavelmente atingiu o limite de RAM ou está sofrendo com
 *   fragmentação/lock interno do alocador.
 */
