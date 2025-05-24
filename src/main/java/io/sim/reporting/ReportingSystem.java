package io.sim.reporting;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import io.sim.DrivingData;
import io.sim.EnvSimulator;

/**
 * Classe responsável por integrar os módulos de relatório e gráficos com o simulador.
 * Implementa o padrão Singleton para garantir uma única instância de integração.
 */
public class ReportingSystem {
    
    private static ReportingSystem instance;
    
    // Referência para os geradores de relatório e gráficos
    private final ExcelReportGenerator excelGenerator;
    private final RealTimeChartManager chartManager;
    
    // Executor para geração periódica de relatórios
    private final ScheduledExecutorService scheduler;
    
    // Diretório base para relatórios e gráficos
    private String baseDirectory = "/home/ubuntu/reporting/";
    
    // Intervalo para geração automática de relatórios (em minutos)
    private int autoReportInterval = 5;
    
    // Flag para controle de execução
    private boolean running = false;
    
    /**
     * Construtor privado para implementar o padrão Singleton.
     */
    private ReportingSystem() {
        // Inicializa os geradores
        excelGenerator = ExcelReportGenerator.getInstance();
        chartManager = RealTimeChartManager.getInstance();
        
        // Configura os diretórios
        setupDirectories();
        
        // Inicializa o scheduler
        scheduler = Executors.newScheduledThreadPool(1);
    }
    
    /**
     * Obtém a instância única do sistema de relatórios.
     * 
     * @return Instância do ReportingSystem
     */
    public static synchronized ReportingSystem getInstance() {
        if (instance == null) {
            instance = new ReportingSystem();
        }
        return instance;
    }
    
    /**
     * Configura os diretórios para relatórios e gráficos.
     */
    private void setupDirectories() {
        // Cria o diretório base se não existir
        File baseDir = new File(baseDirectory);
        if (!baseDir.exists()) {
            baseDir.mkdirs();
        }
        
        // Cria o diretório para relatórios Excel
        File excelDir = new File(baseDirectory + "excel/");
        if (!excelDir.exists()) {
            excelDir.mkdirs();
        }
        
        // Cria o diretório para gráficos
        File chartDir = new File(baseDirectory + "charts/");
        if (!chartDir.exists()) {
            chartDir.mkdirs();
        }
        
        // Configura os diretórios nos geradores
        excelGenerator.setReportDirectory(excelDir.getAbsolutePath() + "/");
        chartManager.setChartDirectory(chartDir.getAbsolutePath() + "/");
    }
    
    /**
     * Inicia o sistema de relatórios.
     */
    public void start() {
        if (running) {
            return;
        }
        
        running = true;
        
        // Inicializa a interface gráfica para os gráficos em tempo real
        chartManager.initializeGUI();
        
        // Agenda a geração periódica de relatórios
        scheduler.scheduleAtFixedRate(this::generatePeriodicReports, 
                autoReportInterval, autoReportInterval, TimeUnit.MINUTES);
        
        System.out.println("Sistema de relatórios iniciado com sucesso.");
    }
    
    /**
     * Para o sistema de relatórios.
     */
    public void stop() {
        if (!running) {
            return;
        }
        
        running = false;
        
        // Gera relatórios finais
        try {
            generateFinalReports();
        } catch (IOException e) {
            System.err.println("Erro ao gerar relatórios finais: " + e.getMessage());
        }
        
        // Encerra o scheduler
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
        }
        
        // Encerra o gerenciador de gráficos
        chartManager.shutdown();
        
        System.out.println("Sistema de relatórios encerrado.");
    }
    
    /**
     * Adiciona dados de condução ao sistema de relatórios.
     * 
     * @param data Dados de condução
     */
    public void addDrivingData(DrivingData data) {
        if (data == null || !running) {
            return;
        }
        
        // Adiciona os dados ao gerador de relatórios Excel
        // (O chartManager já recebe os dados via listener)
        excelGenerator.addDrivingData(data);
    }
    
    /**
     * Gera relatórios periódicos.
     */
    private void generatePeriodicReports() {
        try {
            // Gera relatório Excel consolidado
            String excelReport = excelGenerator.generateConsolidatedReport();
            
            // Salva os gráficos atuais
            String[] chartFiles = chartManager.saveAllCharts();
            
            System.out.println("Relatórios periódicos gerados com sucesso:");
            System.out.println("- Excel: " + excelReport);
            System.out.println("- Gráficos: " + chartFiles.length + " arquivos");
        } catch (Exception e) {
            System.err.println("Erro ao gerar relatórios periódicos: " + e.getMessage());
        }
    }
    
    /**
     * Gera relatórios finais ao encerrar a simulação.
     * 
     * @throws IOException Se ocorrer um erro ao gerar os relatórios
     */
    public void generateFinalReports() throws IOException {
        // Gera relatório Excel consolidado
        String excelReport = excelGenerator.generateConsolidatedReport();
        
        // Salva os gráficos atuais
        String[] chartFiles = chartManager.saveAllCharts();
        
        System.out.println("Relatórios finais gerados com sucesso:");
        System.out.println("- Excel: " + excelReport);
        System.out.println("- Gráficos: " + chartFiles.length + " arquivos");
    }
    
    /**
     * Define o intervalo para geração automática de relatórios.
     * 
     * @param minutes Intervalo em minutos
     */
    public void setAutoReportInterval(int minutes) {
        if (minutes <= 0) {
            throw new IllegalArgumentException("O intervalo deve ser maior que zero");
        }
        
        this.autoReportInterval = minutes;
        
        // Reconfigura o scheduler se estiver em execução
        if (running) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
            }
            
            // Reinicia o scheduler
            scheduler.scheduleAtFixedRate(this::generatePeriodicReports, 
                    autoReportInterval, autoReportInterval, TimeUnit.MINUTES);
        }
    }
    
    /**
     * Obtém o gerador de relatórios Excel.
     * 
     * @return Gerador de relatórios Excel
     */
    public ExcelReportGenerator getExcelGenerator() {
        return excelGenerator;
    }
    
    /**
     * Obtém o gerenciador de gráficos em tempo real.
     * 
     * @return Gerenciador de gráficos
     */
    public RealTimeChartManager getChartManager() {
        return chartManager;
    }
    
    /**
     * Verifica se o sistema de relatórios está em execução.
     * 
     * @return true se estiver em execução, false caso contrário
     */
    public boolean isRunning() {
        return running;
    }
}
