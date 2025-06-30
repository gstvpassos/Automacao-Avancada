package io.sim.reporting;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList; // Importar
//import java.util.List;    // Importar
import java.util.Map;     // Importar
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level; // Importar
import java.util.logging.Logger; // Importar


import io.sim.DrivingData;
import io.sim.MobilityCompany; // Importar

/**
 * Classe responsável por integrar os módulos de relatório e gráficos com o simulador.
 * Implementa o padrão Singleton para garantir uma única instância de integração.
 */
public class ReportingSystem implements MobilityCompany.DrivingDataListener{
    
    private static final Logger logger = Logger.getLogger(ReportingSystem.class.getName()); // Adicionar logger
    private static ReportingSystem instance;
    
    // Referência para os geradores de relatório e gráficos
    private final ExcelReportGenerator excelGenerator;
    private final RealTimeChartManager chartManager;
    
    // Executor para geração periódica de relatórios
    private final ScheduledExecutorService scheduler;
    
    // Diretório base para relatórios e gráficos
    private String baseDirectory = "reporting/";
    
    // Intervalo para geração automática de relatórios (em minutos)
    private int autoReportInterval = 5;
    
    // Flag para controle de execução
    private boolean running = false;
    
    private MobilityCompany mobilityCompany;

    /**
     * Construtor privado para implementar o padrão Singleton.
     */
    private ReportingSystem(MobilityCompany company) {
        this.mobilityCompany = company;
        if (this.mobilityCompany != null) {
            this.mobilityCompany.addDrivingDataListener(this); // Registra-se como listener
        } else {
            logger.warning("ReportingSystem: MobilityCompany não fornecida na inicialização. Gráficos em tempo real podem não funcionar via listener.");
        }

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
    public static synchronized ReportingSystem getInstance(MobilityCompany company) {
        if (instance == null) {
            instance = new ReportingSystem(company);
        }
        return instance;
    }
    
    public void setMobilityCompany(MobilityCompany company) {
        if (this.mobilityCompany == null && company != null) {
            this.mobilityCompany = company;
            this.mobilityCompany.addDrivingDataListener(this);
            logger.info("ReportingSystem: MobilityCompany definida e listener registrado.");
        } else if (company == null) {
            if (this.mobilityCompany != null) {
                this.mobilityCompany.removeDrivingDataListener(this);
            }
            this.mobilityCompany = null;
        }
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
    @Override
    public void onNewDrivingData(DrivingData data) {
        if (data == null || !running) {
            return;
        }
        logger.info("REPORTSYS_ON_NEW_DATA (Listener da MobilityCompany): Recebido DrivingData para Car: " + data.getCarID() + ", TS: " + data.getTimeStamp() + ". Atualizando ChartManager.");
        // Atualiza os gráficos em tempo real com o novo dado
        if (chartManager != null) {
            chartManager.onNewDrivingData(data);
        }
    }
    
    private void generatePeriodicReports() {
        try {
            if (this.mobilityCompany == null) {
                logger.warning("ReportingSystem: MobilityCompany não disponível para relatórios periódicos.");
                return;
            }
            Map<String, ArrayList<DrivingData>> allData = this.mobilityCompany.getConsolidatedCarDrivingReports();

            if (allData == null || allData.isEmpty()) { // Adicionar verificação de null também
                logger.info("ReportingSystem: Nenhum dado disponível na MobilityCompany para relatório periódico Excel.");
            } else {
                logger.info("ReportingSystem: Gerando relatório Excel periódico com dados de " + allData.size() + " carros.");
                String excelReport = excelGenerator.generateConsolidatedReport(allData); // Passa os dados
                logger.info("ReportingSystem: Relatório Excel periódico gerado (ou tentado): " + excelReport);
            }

            // Para gráficos, saveAllCharts pega os dados das TimeSeries internas do ChartManager
            logger.info("ReportingSystem: Salvando gráficos periódicos...");
            String[] chartFiles = chartManager.saveAllCharts();
            for(String chartFile : chartFiles){
                if(chartFile != null) logger.info("ReportingSystem: Gráfico salvo: " + chartFile);
            }

        } catch (IllegalArgumentException iae) { 
            logger.warning("ReportingSystem: Não foi possível gerar relatório periódico: " + iae.getMessage());
        } catch (Exception e) {
            logger.log(Level.SEVERE, "ReportingSystem: Erro ao gerar relatórios periódicos: " + e.getMessage(), e);
        }
    }
    
    /**
     * Gera relatórios finais ao encerrar a simulação.
     * 
     * @throws IOException Se ocorrer um erro ao gerar os relatórios
     */
    public void generateFinalReports() throws IOException {
    // ... (lógica similar para obter e logar o tamanho de allData) ...
        Map<String, ArrayList<DrivingData>> allData = mobilityCompany.getConsolidatedCarDrivingReports();
        if (allData == null || allData.isEmpty()) {
            logger.warning("ReportingSystem: Não há dados de condução disponíveis da MobilityCompany para gerar relatório Excel final.");
            // Não lança exceção para permitir que os gráficos (se houver) sejam salvos
        } else {
            logger.info("ReportingSystem: Gerando relatório Excel final com dados de " + allData.size() + " carros.");
            String excelReportPath = excelGenerator.generateConsolidatedReport(allData);
            logger.info("ReportingSystem: Relatório Excel final gerado (ou tentado): " + excelReportPath);
        }

        logger.info("ReportingSystem: Salvando gráficos finais...");
        String[] chartFilePaths = chartManager.saveAllCharts();
        for(String chartFile : chartFilePaths){
            if(chartFile != null) logger.info("ReportingSystem: Gráfico final salvo: " + chartFile);
            else logger.info("ReportingSystem: arquivo nulo: " + chartFile);
        }
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
