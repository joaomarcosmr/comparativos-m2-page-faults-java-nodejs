const fs = require('fs');
const path = require('path');

const REPORT_DIR = path.join(__dirname, '..', 'reports', 'memory');
const NODEJS_RESULT = path.join(REPORT_DIR, 'nodejs-final.json');
const JAVA_RESULT = path.join(REPORT_DIR, 'java-result-final.json');

function loadResults(filePath) {
    try {
        const content = fs.readFileSync(filePath, 'utf8');
        return JSON.parse(content);
    } catch (error) {
        console.error(`Erro ao carregar ${filePath}: ${error.message}`);
        return null;
    }
}

function formatSeconds(seconds) {
    if (seconds === null || seconds === undefined) return 'n/d';
    return `${seconds.toFixed(4)}s`;
}

function formatNumber(num) {
    if (num === null || num === undefined) return 'n/d';
    return num.toLocaleString('pt-BR');
}

function calculateSpeedup(nodejsTime, javaTime) {
    if (!nodejsTime || !javaTime) return 'n/d';
    const speedup = nodejsTime / javaTime;
    if (speedup > 1) {
        return `Java ${speedup.toFixed(2)}x mais rápido`;
    } else {
        return `Node.js ${(1/speedup).toFixed(2)}x mais rápido`;
    }
}

function compareResults() {
    console.log('\n╔═══════════════════════════════════════════════════════════════════════════╗');
    console.log('║         COMPARAÇÃO COMPLETA: NODE.JS vs JAVA (COM PAGE FAULTS)           ║');
    console.log('╚═══════════════════════════════════════════════════════════════════════════╝\n');

    const nodejsResults = loadResults(NODEJS_RESULT);
    const javaResults = loadResults(JAVA_RESULT);

    if (!nodejsResults || !javaResults) {
        console.error('\n❌ Não foi possível carregar os resultados.\n');
        console.log(`   Procurando: ${NODEJS_RESULT}`);
        console.log(`   Procurando: ${JAVA_RESULT}\n`);
        process.exit(1);
    }

    console.log(`📊 Versões:`);
    console.log(`   Node.js: ${nodejsResults[0]?.nodeVersion || 'n/d'}`);
    console.log(`   Java:    ${javaResults[0]?.javaVersion || 'n/d'}\n`);

    // Comparar cada cenário
    for (let i = 0; i < nodejsResults.length; i++) {
        const nodejsResult = nodejsResults[i];
        const javaResult = javaResults[i];

        if (!nodejsResult || !javaResult) continue;

        console.log(`\n┌─────────────────────────────────────────────────────────────────────────┐`);
        console.log(`│ Cenário: ${nodejsResult.scenarioId.padEnd(60)} │`);
        console.log(`│ Tamanho: ${nodejsResult.sizeMb} MB | Iterações: ${nodejsResult.iterations}${' '.repeat(40)} │`);
        console.log(`├─────────────────────────────────────────────────────────────────────────┤`);
        console.log(`│ Métrica              │    Node.js    │     Java      │   Comparação    │`);
        console.log(`├──────────────────────┼───────────────┼───────────────┼─────────────────┤`);

        const metrics = [
            { name: 'Alocação', key: 'allocationSeconds' },
            { name: 'Aloca + Libera', key: 'allocateAndFreeSeconds' },
            { name: 'Escrita', key: 'writesSeconds' },
            { name: 'Leitura', key: 'readsSeconds' },
        ];

        for (const metric of metrics) {
            const nodejsValue = nodejsResult.metrics[metric.key];
            const javaValue = javaResult.metrics[metric.key];
            const comparison = calculateSpeedup(nodejsValue, javaValue);
            
            console.log(
                `│ ${metric.name.padEnd(20)} │ ${formatSeconds(nodejsValue).padStart(13)} │ ${formatSeconds(javaValue).padStart(13)} │ ${comparison.padEnd(15)} │`
            );
        }

        // Page Faults
        const nodejsMinor = nodejsResult.metrics.pageFaultsMinor;
        const javaMinor = javaResult.metrics.pageFaultsMinor;
        const nodejsMajor = nodejsResult.metrics.pageFaultsMajor;
        const javaMajor = javaResult.metrics.pageFaultsMajor;

        console.log(`├──────────────────────┼───────────────┼───────────────┼─────────────────┤`);
        console.log(
            `│ Page Faults (minor)  │ ${formatNumber(nodejsMinor).padStart(13)} │ ${formatNumber(javaMinor).padStart(13)} │ ${' '.repeat(15)} │`
        );
        console.log(
            `│ Page Faults (major)  │ ${formatNumber(nodejsMajor).padStart(13)} │ ${formatNumber(javaMajor).padStart(13)} │ ${' '.repeat(15)} │`
        );
        console.log(`└──────────────────────┴───────────────┴───────────────┴─────────────────┘`);
    }

    console.log('\n');
    generateSummary(nodejsResults, javaResults);
    generatePageFaultAnalysis(nodejsResults, javaResults);
}

function generateSummary(nodejsResults, javaResults) {
    console.log('╔═══════════════════════════════════════════════════════════════════════════╗');
    console.log('║                        RESUMO DE PERFORMANCE                              ║');
    console.log('╚═══════════════════════════════════════════════════════════════════════════╝\n');

    const metrics = ['allocationSeconds', 'allocateAndFreeSeconds', 'writesSeconds', 'readsSeconds'];
    const avgNodejs = {};
    const avgJava = {};

    metrics.forEach(metric => {
        avgNodejs[metric] = nodejsResults.reduce((sum, r) => sum + (r.metrics[metric] || 0), 0) / nodejsResults.length;
        avgJava[metric] = javaResults.reduce((sum, r) => sum + (r.metrics[metric] || 0), 0) / javaResults.length;
    });

    console.log('📈 Médias de Tempo de Execução:\n');
    
    const metricNames = {
        allocationSeconds: 'Alocação',
        allocateAndFreeSeconds: 'Aloca + Libera',
        writesSeconds: 'Escrita',
        readsSeconds: 'Leitura',
    };

    let nodejsWins = 0;
    let javaWins = 0;

    metrics.forEach(metric => {
        const comparison = calculateSpeedup(avgNodejs[metric], avgJava[metric]);
        const winner = avgNodejs[metric] < avgJava[metric] ? '🏆 Node.js' : '🏆 Java';
        
        if (avgNodejs[metric] < avgJava[metric]) nodejsWins++;
        else javaWins++;

        console.log(`   ${metricNames[metric].padEnd(20)}: ${comparison.padEnd(30)} ${winner}`);
    });

    console.log('\n─────────────────────────────────────────────────────────────────────────\n');
    console.log(`🏁 Placar Final: Node.js ${nodejsWins} x ${javaWins} Java\n`);

    if (nodejsWins > javaWins) {
        console.log('🎉 Node.js foi mais rápido na maioria dos testes!\n');
    } else if (javaWins > nodejsWins) {
        console.log('🎉 Java foi mais rápido na maioria dos testes!\n');
    } else {
        console.log('🤝 Empate! Ambos tiveram performance semelhante.\n');
    }
}

function generatePageFaultAnalysis(nodejsResults, javaResults) {
    console.log('╔═══════════════════════════════════════════════════════════════════════════╗');
    console.log('║                     ANÁLISE DE PAGE FAULTS                                ║');
    console.log('╚═══════════════════════════════════════════════════════════════════════════╝\n');

    console.log('📊 Page Faults por Tamanho de Memória:\n');

    for (let i = 0; i < nodejsResults.length; i++) {
        const nodejsResult = nodejsResults[i];
        const javaResult = javaResults[i];

        console.log(`   ${nodejsResult.sizeMb} MB:`);
        console.log(`      Node.js - Minor: ${formatNumber(nodejsResult.metrics.pageFaultsMinor)}, Major: ${formatNumber(nodejsResult.metrics.pageFaultsMajor)}`);
        console.log(`      Java    - Minor: ${formatNumber(javaResult.metrics.pageFaultsMinor)}, Major: ${formatNumber(javaResult.metrics.pageFaultsMajor)}`);
        console.log();
    }

    console.log('💡 Interpretação:\n');
    console.log('   • Minor Page Faults: Página está em memória mas não no TLB (rápido)');
    console.log('   • Major Page Faults: Página precisa ser carregada do disco (lento)');
    console.log('   • Node.js: Valores reais do sistema operacional');
    console.log('   • Java: Proxy baseado em alocações de memória e GC\n');
}

// Executar comparação
if (require.main === module) {
    compareResults();
}

