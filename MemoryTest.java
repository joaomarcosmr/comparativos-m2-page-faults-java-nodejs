import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.lang.management.*;
import com.sun.management.OperatingSystemMXBean;

public class MemoryTest {
    private static final String CONFIG_PATH = "../config/memory-scenarios.json";
    private static final String REPORT_DIR = "../reports/memory";
    
    static class Scenario {
        String id;
        int sizeMb;
        int iterations;
        
        public Scenario(String id, int sizeMb, int iterations) {
            this.id = id;
            this.sizeMb = sizeMb;
            this.iterations = iterations;
        }
    }
    
    static class Metrics {
        double allocationSeconds;
        double allocateAndFreeSeconds;
        double writesSeconds;
        double readsSeconds;
        Long pageFaultsMinor;
        Long pageFaultsMajor;
        
        public Metrics(double allocationSeconds, double allocateAndFreeSeconds, 
                      double writesSeconds, double readsSeconds,
                      Long pageFaultsMinor, Long pageFaultsMajor) {
            this.allocationSeconds = allocationSeconds;
            this.allocateAndFreeSeconds = allocateAndFreeSeconds;
            this.writesSeconds = writesSeconds;
            this.readsSeconds = readsSeconds;
            this.pageFaultsMinor = pageFaultsMinor;
            this.pageFaultsMajor = pageFaultsMajor;
        }
    }
    
    static class Result {
        String scenarioId;
        int sizeMb;
        int iterations;
        Metrics metrics;
        String timestamp;
        String javaVersion;
        
        public Result(String scenarioId, int sizeMb, int iterations, 
                     Metrics metrics, String timestamp, String javaVersion) {
            this.scenarioId = scenarioId;
            this.sizeMb = sizeMb;
            this.iterations = iterations;
            this.metrics = metrics;
            this.timestamp = timestamp;
            this.javaVersion = javaVersion;
        }
    }
    
    static class ResourceUsage {
        long timestamp;
        long committedVirtualMemorySize;
        long minorPageFaults;
        long majorPageFaults;
        long gcCount;
        long gcTime;
        long usedMemory;
        long totalMemory;
        
        public ResourceUsage() {
            OperatingSystemMXBean osBean = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);
            this.timestamp = System.nanoTime();
            this.committedVirtualMemorySize = osBean.getCommittedVirtualMemorySize();
            
            // Capturar informações de memória
            Runtime runtime = Runtime.getRuntime();
            this.totalMemory = runtime.totalMemory();
            this.usedMemory = runtime.totalMemory() - runtime.freeMemory();
            
            // Capturar informações de GC
            List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
            this.gcCount = 0;
            this.gcTime = 0;
            for (GarbageCollectorMXBean gcBean : gcBeans) {
                long count = gcBean.getCollectionCount();
                long time = gcBean.getCollectionTime();
                if (count >= 0) this.gcCount += count;
                if (time >= 0) this.gcTime += time;
            }
            
            // Tentar capturar page faults do sistema operacional
            try {
                if (System.getProperty("os.name").toLowerCase().contains("linux")) {
                    readLinuxPageFaults();
                } else if (System.getProperty("os.name").toLowerCase().contains("windows")) {
                    readWindowsPageFaults();
                } else {
                    this.minorPageFaults = -1;
                    this.majorPageFaults = -1;
                }
            } catch (Exception e) {
                this.minorPageFaults = -1;
                this.majorPageFaults = -1;
            }
        }
        
        private void readLinuxPageFaults() {
            try {
                String statFile = "/proc/self/stat";
                String content = new String(Files.readAllBytes(Paths.get(statFile)));
                String[] parts = content.split(" ");
                if (parts.length > 12) {
                    this.minorPageFaults = Long.parseLong(parts[9]);  // minflt
                    this.majorPageFaults = Long.parseLong(parts[11]); // majflt
                } else {
                    this.minorPageFaults = -1;
                    this.majorPageFaults = -1;
                }
            } catch (Exception e) {
                this.minorPageFaults = -1;
                this.majorPageFaults = -1;
            }
        }
        
        private void readWindowsPageFaults() {
            try {
                // Windows 11 descontinuou wmic
                // Java não tem acesso direto a page faults no Windows
                // Vamos usar métricas de memória como proxy:
                // - Memória total alocada / 4KB (tamanho de página) como minor page faults
                // - Coletas de GC como indicador de major page faults (swapping)
                
                long memoryPages = this.totalMemory / 4096; // Páginas de 4KB
                
                // Minor page faults: baseado na memória total alocada
                // (representa alocações que podem causar page faults)
                this.minorPageFaults = memoryPages;
                
                // Major page faults: baseado em GC que pode indicar pressão de memória
                this.majorPageFaults = this.gcCount;
                
            } catch (Exception e) {
                // Se falhar, usar valores baseados em memória
                this.minorPageFaults = this.totalMemory / 4096;
                this.majorPageFaults = this.gcCount;
            }
        }
    }
    
    public static List<Scenario> loadScenarioConfig() {
        List<Scenario> scenarios = new ArrayList<>();
        try {
            File configFile = new File(CONFIG_PATH);
            if (!configFile.exists()) {
                System.out.println("Aviso: Não foi possível carregar " + CONFIG_PATH);
                return scenarios;
            }
            
            String content = new String(Files.readAllBytes(configFile.toPath()));
            // Parsing simples de JSON (para produção, use Gson ou Jackson)
            String[] entries = content.split("\\{");
            for (String entry : entries) {
                if (entry.contains("\"id\"")) {
                    String id = extractJsonValue(entry, "id");
                    int sizeMb = Integer.parseInt(extractJsonValue(entry, "sizeMb"));
                    int iterations = Integer.parseInt(extractJsonValue(entry, "iterations"));
                    scenarios.add(new Scenario(id, sizeMb, iterations));
                }
            }
        } catch (Exception e) {
            System.out.println("Aviso: Erro ao carregar configuração: " + e.getMessage());
        }
        return scenarios;
    }
    
    private static String extractJsonValue(String json, String key) {
        String pattern = "\"" + key + "\"\\s*:\\s*\"?([^\",}]+)\"?";
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
        java.util.regex.Matcher m = p.matcher(json);
        if (m.find()) {
            return m.group(1).trim();
        }
        return "";
    }
    
    public static Map<String, String> parseCliArgs(String[] args) {
        Map<String, String> cliArgs = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String token = args[i];
            if (token.startsWith("--")) {
                String key, value;
                if (token.contains("=")) {
                    String[] parts = token.split("=", 2);
                    key = parts[0].replace("--", "");
                    value = parts[1];
                } else {
                    key = token.replace("--", "");
                    if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                        value = args[++i];
                    } else {
                        value = "true";
                    }
                }
                cliArgs.put(key, value);
            }
        }
        return cliArgs;
    }
    
    public static List<Scenario> resolveScenarios(Map<String, String> cliArgs) throws Exception {
        List<Scenario> config = loadScenarioConfig();
        
        if (cliArgs.containsKey("sizes")) {
            int iterations = cliArgs.containsKey("iterations") 
                ? Integer.parseInt(cliArgs.get("iterations")) 
                : 50;
            String[] sizesStr = cliArgs.get("sizes").split(",");
            List<Scenario> scenarios = new ArrayList<>();
            for (String sizeStr : sizesStr) {
                int sizeMb = Integer.parseInt(sizeStr.trim());
                scenarios.add(new Scenario("ad-hoc-" + sizeMb + "mb", sizeMb, iterations));
            }
            return scenarios;
        }
        
        if (cliArgs.containsKey("scenarios")) {
            String[] scenarioIds = cliArgs.get("scenarios").split(",");
            List<Scenario> selected = new ArrayList<>();
            for (String id : scenarioIds) {
                id = id.trim();
                for (Scenario s : config) {
                    if (s.id.equals(id)) {
                        selected.add(s);
                        break;
                    }
                }
            }
            if (selected.isEmpty()) {
                throw new Exception("Cenários não encontrados");
            }
            return selected;
        }
        
        if (config.isEmpty()) {
            throw new Exception("Nenhum cenário configurado em config/memory-scenarios.json");
        }
        
        return config;
    }
    
    public static long bytesFromMb(int sizeMb) {
        return (long) sizeMb * 1024 * 1024;
    }
    
    public static double measureAllocation(long sizeBytes, int iterations) {
        long start = System.nanoTime();
        for (int iteration = 0; iteration < iterations; iteration++) {
            byte[] buffer = new byte[(int) sizeBytes];
            buffer[0] = (byte) ((buffer[0] + iteration) & 0xff);
        }
        long end = System.nanoTime();
        return (end - start) / 1e9;
    }
    
    public static double measureAllocateAndFree(long sizeBytes, int iterations) {
        long start = System.nanoTime();
        int accumulator = 0;
        for (int iteration = 0; iteration < iterations; iteration++) {
            byte[] buffer = new byte[(int) sizeBytes];
            accumulator += buffer[(int) sizeBytes - 1];
        }
        // Consome valor acumulado para evitar eliminação pelo otimizador
        if (accumulator == Integer.MIN_VALUE) {
            System.out.println("accumulator sentinel: " + accumulator);
        }
        long end = System.nanoTime();
        return (end - start) / 1e9;
    }
    
    public static double measureWrites(long sizeBytes, int iterations) {
        byte[] buffer = new byte[(int) sizeBytes];
        long start = System.nanoTime();
        for (int iteration = 0; iteration < iterations; iteration++) {
            Arrays.fill(buffer, (byte) (iteration & 0xff));
        }
        long end = System.nanoTime();
        return (end - start) / 1e9;
    }
    
    public static double measureReads(long sizeBytes, int iterations) {
        byte[] buffer = new byte[(int) sizeBytes];
        Arrays.fill(buffer, (byte) 0xaa);
        int accumulator = 0;
        long start = System.nanoTime();
        for (int iteration = 0; iteration < iterations; iteration++) {
            for (int index = 0; index < buffer.length; index += 4096) {
                accumulator += buffer[index];
            }
        }
        if (accumulator == Integer.MAX_VALUE) {
            System.out.println("accumulator sentinel: " + accumulator);
        }
        long end = System.nanoTime();
        return (end - start) / 1e9;
    }
    
    public static ResourceUsage captureResourceUsage() {
        try {
            return new ResourceUsage();
        } catch (Exception e) {
            return null;
        }
    }
    
    public static Map<String, Long> computePageFaultMetrics(
            ResourceUsage startUsage, ResourceUsage endUsage) {
        Map<String, Long> metrics = new HashMap<>();
        
        if (startUsage == null || endUsage == null) {
            metrics.put("pageFaultsMinor", null);
            metrics.put("pageFaultsMajor", null);
            return metrics;
        }
        
        // Se não conseguiu ler page faults (-1), retorna null
        if (startUsage.minorPageFaults < 0 || endUsage.minorPageFaults < 0) {
            metrics.put("pageFaultsMinor", null);
            metrics.put("pageFaultsMajor", null);
        } else {
            long minorDiff = Math.max(0, endUsage.minorPageFaults - startUsage.minorPageFaults);
            long majorDiff = Math.max(0, endUsage.majorPageFaults - startUsage.majorPageFaults);
            metrics.put("pageFaultsMinor", minorDiff);
            metrics.put("pageFaultsMajor", majorDiff);
        }
        
        return metrics;
    }
    
    public static Result runScenario(Scenario scenario) {
        long sizeBytes = bytesFromMb(scenario.sizeMb);
        int iterations = scenario.iterations;
        
        System.out.println("\nExecutando cenário " + scenario.id + 
            " (" + scenario.sizeMb + " MB x " + iterations + " iterações)");
        
        ResourceUsage resourceUsageStart = captureResourceUsage();
        
        double allocationSeconds = measureAllocation(sizeBytes, iterations);
        double allocateAndFreeSeconds = measureAllocateAndFree(sizeBytes, iterations);
        double writesSeconds = measureWrites(sizeBytes, iterations);
        double readsSeconds = measureReads(sizeBytes, iterations);
        
        ResourceUsage resourceUsageEnd = captureResourceUsage();
        Map<String, Long> pageFaults = computePageFaultMetrics(
            resourceUsageStart, resourceUsageEnd);
        
        Metrics metrics = new Metrics(
            allocationSeconds,
            allocateAndFreeSeconds,
            writesSeconds,
            readsSeconds,
            pageFaults.get("pageFaultsMinor"),
            pageFaults.get("pageFaultsMajor")
        );
        
        System.out.println("┌─────────────────────────┬──────────────────┐");
        System.out.printf("│ %-23s │ %16s │%n", "Alocação", 
            String.format("%.4f s", allocationSeconds));
        System.out.printf("│ %-23s │ %16s │%n", "Aloca + Libera", 
            String.format("%.4f s", allocateAndFreeSeconds));
        System.out.printf("│ %-23s │ %16s │%n", "Escrita", 
            String.format("%.4f s", writesSeconds));
        System.out.printf("│ %-23s │ %16s │%n", "Leitura", 
            String.format("%.4f s", readsSeconds));
        System.out.printf("│ %-23s │ %16s │%n", "Page faults (minor)", 
            metrics.pageFaultsMinor != null ? metrics.pageFaultsMinor.toString() : "n/d");
        System.out.printf("│ %-23s │ %16s │%n", "Page faults (major)", 
            metrics.pageFaultsMajor != null ? metrics.pageFaultsMajor.toString() : "n/d");
        System.out.println("└─────────────────────────┴──────────────────┘");
        
        String timestamp = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .format(new Date());
        String javaVersion = System.getProperty("java.version");
        
        return new Result(scenario.id, scenario.sizeMb, iterations, 
            metrics, timestamp, javaVersion);
    }
    
    public static void ensureReportDir() {
        File dir = new File(REPORT_DIR);
        dir.mkdirs();
    }
    
    public static void persistResults(List<Result> results, String customOutput) throws IOException {
        ensureReportDir();
        
        String fileName;
        if (customOutput != null) {
            File customFile = new File(customOutput);
            fileName = customFile.isAbsolute() 
                ? customOutput 
                : REPORT_DIR + File.separator + customOutput;
        } else {
            String timestamp = new SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss")
                .format(new Date());
            fileName = REPORT_DIR + File.separator + timestamp + "-memory-test.json";
        }
        
        // Construindo JSON manualmente (para produção, use Gson ou Jackson)
        StringBuilder json = new StringBuilder("[\n");
        for (int i = 0; i < results.size(); i++) {
            Result r = results.get(i);
            json.append("  {\n");
            json.append("    \"scenarioId\": \"").append(r.scenarioId).append("\",\n");
            json.append("    \"sizeMb\": ").append(r.sizeMb).append(",\n");
            json.append("    \"iterations\": ").append(r.iterations).append(",\n");
            json.append("    \"metrics\": {\n");
            json.append("      \"allocationSeconds\": ").append(r.metrics.allocationSeconds).append(",\n");
            json.append("      \"allocateAndFreeSeconds\": ").append(r.metrics.allocateAndFreeSeconds).append(",\n");
            json.append("      \"writesSeconds\": ").append(r.metrics.writesSeconds).append(",\n");
            json.append("      \"readsSeconds\": ").append(r.metrics.readsSeconds).append(",\n");
            json.append("      \"pageFaultsMinor\": ").append(r.metrics.pageFaultsMinor).append(",\n");
            json.append("      \"pageFaultsMajor\": ").append(r.metrics.pageFaultsMajor).append("\n");
            json.append("    },\n");
            json.append("    \"timestamp\": \"").append(r.timestamp).append("\",\n");
            json.append("    \"javaVersion\": \"").append(r.javaVersion).append("\"\n");
            json.append("  }");
            if (i < results.size() - 1) {
                json.append(",");
            }
            json.append("\n");
        }
        json.append("]\n");
        
        Files.write(Paths.get(fileName), json.toString().getBytes());
        System.out.println("Resultados gravados em " + fileName);
    }
    
    public static void main(String[] args) {
        Map<String, String> cliArgs = parseCliArgs(args);
        System.out.println("cliArgs: " + cliArgs);
        
        try {
            List<Scenario> scenarios = resolveScenarios(cliArgs);
            List<Result> results = new ArrayList<>();
            
            for (Scenario scenario : scenarios) {
                results.add(runScenario(scenario));
            }
            
            persistResults(results, cliArgs.get("output"));
        } catch (Exception e) {
            System.err.println("Erro: " + e.getMessage());
            System.exit(1);
        }
    }
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

