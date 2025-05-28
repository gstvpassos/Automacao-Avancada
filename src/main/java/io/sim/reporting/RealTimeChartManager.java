package io.sim.reporting;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.ChartUtils;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.DateAxis;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.chart.title.TextTitle;
import org.jfree.data.time.Millisecond;
import org.jfree.data.time.TimeSeries;
import org.jfree.data.time.TimeSeriesCollection;
//import org.jfree.data.xy.XYDataset;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;

import io.sim.DrivingData;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * Classe responsável por gerar gráficos em tempo real a partir dos dados de condução.
 * Implementa o padrão Singleton para garantir uma única instância de gerenciamento de gráficos.
 */
public class RealTimeChartManager implements ExcelReportGenerator.DrivingDataListener {
    
     private static final Logger logger = Logger.getLogger(RealTimeChartManager.class.getName()); // Adicionar logger
   
    private static RealTimeChartManager instance;
    
    // Armazena séries temporais por veículo e tipo de dado
    private final Map<String, Map<ChartType, TimeSeries>> timeSeriesMap;
    
    // Armazena TimeSeries por ID do Veículo e depois por Tipo de Gráfico
    private final Map<String, Map<ChartType, TimeSeries>> timeSeriesByVehicle;

    // Armazena os painéis de gráficos por tipo
    //private final Map<ChartType, ChartPanel> chartPanels;
    // Armazena os JFrames para cada tipo de gráfico
    private final Map<ChartType, JFrame> chartFrames;
    // Armazena os datasets para cada tipo de gráfico (para facilitar a atualização)
    private final Map<ChartType, TimeSeriesCollection> chartDatasets;
    
    // Janela principal para exibição dos gráficos
    private JFrame mainFrame;
    
    // Diretório para salvar gráficos
    private String chartDirectory = "/home/ubuntu/reporting/charts/";
    
    // Executor para atualização periódica dos gráficos
    private final ScheduledExecutorService scheduler;
    
    // Formato de data para nomes de arquivos
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");
    
    // Tipos de gráficos disponíveis
    public enum ChartType {
        SPEED("Velocidade (m/s)"),
        FUEL_CONSUMPTION("Consumo de Combustível (mg/s)"),
        CO2_EMISSION("Emissão de CO2 (mg/s)"),
        DISTANCE("Distância Percorrida (m)"),
        HC_EMISSION("Emissão de HC (mg/s)");
        
        private final String label;
        
        ChartType(String label) {
            this.label = label;
        }
        
        public String getLabel() {
            return label;
        }
    }
    
    /**
     * Construtor privado para implementar o padrão Singleton.
     */
    private RealTimeChartManager() {
        timeSeriesByVehicle = new ConcurrentHashMap<>();
        timeSeriesMap = new ConcurrentHashMap<>();
        chartFrames = new ConcurrentHashMap<>();
        chartDatasets = new ConcurrentHashMap<>(); // Para armazenar os datasets dos gráficos principais
        scheduler = Executors.newScheduledThreadPool(1);

        File dir = new File(chartDirectory);
        if (!dir.exists()) {
            if(dir.mkdirs()){ logger.info("Diretório de gráficos criado: " + dir.getAbsolutePath()); }
            else { logger.severe("Falha ao criar diretório de gráficos: " + dir.getAbsolutePath()); }
        }
        
        // Atualiza os gráficos periodicamente (por exemplo, a cada segundo)
        scheduler.scheduleAtFixedRate(this::updateAllChartDisplays, 1, 1, TimeUnit.SECONDS);
    }

    /**
     * Obtém a instância única do gerenciador de gráficos.
     * 
     * @return Instância do RealTimeChartManager
     */
    public static synchronized RealTimeChartManager getInstance() {
        if (instance == null) {
            instance = new RealTimeChartManager();
        }
        return instance;
    }
    
    /**
     * Define o diretório onde os gráficos serão salvos.
     * 
     * @param directory Caminho do diretório
     */
    public void setChartDirectory(String directory) {
        if (!directory.endsWith("/")) {
            directory += "/";
        }
        this.chartDirectory = directory;
        
        // Cria o diretório se não existir
        File dir = new File(chartDirectory);
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }
    
    /**
     * Inicializa a interface gráfica para exibição dos gráficos em tempo real.
     */
    public void initializeGUI() {
        SwingUtilities.invokeLater(() -> {
            for (ChartType type : ChartType.values()) {
                TimeSeriesCollection dataset = new TimeSeriesCollection();
                chartDatasets.put(type, dataset); // Armazena o dataset para este tipo de gráfico

                JFreeChart chart = createAggregatedChart(type.getLabel(), type.getLabel(), dataset);
                ChartPanel chartPanel = new ChartPanel(chart);
                chartPanel.setPreferredSize(new Dimension(800, 600)); // Tamanho para janela individual
                chartPanel.setMouseZoomable(true);
                chartPanel.setDomainZoomable(true);
                chartPanel.setRangeZoomable(true);

                JFrame frame = new JFrame("Monitoramento em Tempo Real - " + type.getLabel());
                frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE); // Fecha apenas esta janela
                frame.setContentPane(chartPanel);
                frame.pack();
                frame.setLocationByPlatform(true); // Posicionamento automático
                frame.setVisible(true);
                chartFrames.put(type, frame); // Rastreia a janela
                
                // Listener para remover do mapa quando a janela é fechada
                frame.addWindowListener(new java.awt.event.WindowAdapter() {
                    @Override
                    public void windowClosed(java.awt.event.WindowEvent windowEvent) {
                        chartFrames.remove(type);
                        logger.info("Janela do gráfico " + type.getLabel() + " fechada.");
                    }
                });
            }
            logger.info("RealTimeChartManager: Janelas de gráficos individuais inicializadas.");
        });
    }
    
    private JFreeChart createAggregatedChart(String title, String yAxisLabel, TimeSeriesCollection dataset) {
        JFreeChart chart = ChartFactory.createTimeSeriesChart(
                title, "Tempo", yAxisLabel, dataset,
                true, true, false); // Legenda é útil aqui para diferenciar carros

        chart.setBackgroundPaint(Color.WHITE);
        TextTitle subtitle = new TextTitle("Dados de todos os veículos - Atualizado em: " + new SimpleDateFormat("HH:mm:ss").format(new Date()));
        subtitle.setFont(new Font("SansSerif", Font.PLAIN, 10));
        chart.addSubtitle(subtitle);
        
        XYPlot plot = (XYPlot) chart.getPlot();
        plot.setBackgroundPaint(new Color(0xE8E8E8)); // Cor de fundo mais suave
        plot.setDomainGridlinePaint(Color.DARK_GRAY);
        plot.setRangeGridlinePaint(Color.DARK_GRAY);
        
        DateAxis axis = (DateAxis) plot.getDomainAxis();
        axis.setDateFormatOverride(new SimpleDateFormat("HH:mm:ss"));
        
        XYLineAndShapeRenderer renderer = (XYLineAndShapeRenderer) plot.getRenderer();
        renderer.setDefaultShapesVisible(false); // Menos poluição sem shapes por padrão
        renderer.setDrawSeriesLineAsPath(true);
        // Você pode querer um esquema de cores ou strokes diferentes por série aqui
        // para ajudar a distinguir os 100 carros, mas ainda pode ser poluído.

        return chart;
    }

    /**
     * Cria um gráfico para um tipo específico.
     * 
     * @param type Tipo de gráfico
     * @return Gráfico JFreeChart
     */
    private JFreeChart createChart(ChartType type) {
        TimeSeriesCollection dataset = new TimeSeriesCollection();
        
        JFreeChart chart = ChartFactory.createTimeSeriesChart(
                type.getLabel(),  // título
                "Tempo",          // label do eixo x
                type.getLabel(),  // label do eixo y
                dataset,          // dados
                true,             // legenda
                true,             // tooltips
                false             // urls
        );
        
        // Personaliza o gráfico
        chart.setBackgroundPaint(Color.WHITE);
        chart.setBorderPaint(Color.BLACK);
        chart.setBorderStroke(new BasicStroke(2.0f));
        chart.setBorderVisible(true);
        
        // Adiciona um subtítulo
        TextTitle subtitle = new TextTitle("Dados em tempo real");
        subtitle.setFont(new Font("SansSerif", Font.PLAIN, 12));
        chart.addSubtitle(subtitle);
        
        // Personaliza o plot
        XYPlot plot = (XYPlot) chart.getPlot();
        plot.setBackgroundPaint(new Color(245, 245, 245));
        plot.setDomainGridlinePaint(Color.LIGHT_GRAY);
        plot.setRangeGridlinePaint(Color.LIGHT_GRAY);
        plot.setDomainCrosshairVisible(true);
        plot.setRangeCrosshairVisible(true);
        
        // Personaliza o eixo de data
        DateAxis dateAxis = (DateAxis) plot.getDomainAxis();
        dateAxis.setDateFormatOverride(new SimpleDateFormat("HH:mm:ss"));
        
        // Personaliza o renderer
        XYLineAndShapeRenderer renderer = (XYLineAndShapeRenderer) plot.getRenderer();
        renderer.setDefaultShapesVisible(true);
        renderer.setDefaultShapesFilled(true);
        renderer.setDrawSeriesLineAsPath(true);
        
        return chart;
    }
    
    /**
     * Cria um gráfico de barras para comparação entre veículos.
     * 
     * @param type Tipo de dado para comparação
     * @return Gráfico JFreeChart
     */
    public JFreeChart createComparisonChart(ChartType type) {
        XYSeriesCollection dataset = new XYSeriesCollection();
        
        // Adiciona uma série para cada veículo
        for (String vehicleId : timeSeriesMap.keySet()) {
            Map<ChartType, TimeSeries> vehicleData = timeSeriesMap.get(vehicleId);
            TimeSeries timeSeries = vehicleData.get(type);
            
            if (timeSeries != null && timeSeries.getItemCount() > 0) {
                XYSeries series = new XYSeries(vehicleId);
                
                // Calcula a média dos valores
                double sum = 0;
                int count = 0;
                
                for (int i = 0; i < timeSeries.getItemCount(); i++) {
                    sum += timeSeries.getValue(i).doubleValue();
                    count++;
                }
                
                double average = count > 0 ? sum / count : 0;
                series.add(1, average);
                dataset.addSeries(series);
            }
        }
        
        JFreeChart chart = ChartFactory.createXYBarChart(
                "Comparação de " + type.getLabel() + " entre Veículos",
                "Veículo",
                false,
                type.getLabel(),
                dataset,
                PlotOrientation.VERTICAL,
                true,
                true,
                false
        );
        
        // Personaliza o gráfico
        chart.setBackgroundPaint(Color.WHITE);
        
        // Personaliza o plot
        XYPlot plot = (XYPlot) chart.getPlot();
        plot.setBackgroundPaint(new Color(245, 245, 245));
        plot.setDomainGridlinePaint(Color.LIGHT_GRAY);
        plot.setRangeGridlinePaint(Color.LIGHT_GRAY);
        
        // Personaliza o eixo x
        NumberAxis domainAxis = (NumberAxis) plot.getDomainAxis();
        domainAxis.setVisible(false);
        
        return chart;
    }
    
    /**
     * Atualiza os gráficos com os dados mais recentes.
     */
    // public void updateCharts() {
    //     SwingUtilities.invokeLater(() -> {
    //         for (ChartType type : ChartType.values()) {
    //             ChartPanel chartPanel = chartPanels.get(type);
    //             if (chartPanel != null) {
    //                 JFreeChart chart = chartPanel.getChart();
    //                 XYPlot plot = chart.getXYPlot();
    //                 TimeSeriesCollection dataset = (TimeSeriesCollection) plot.getDataset();
                    
    //                 // Limpa o dataset atual
    //                 dataset.removeAllSeries();
                    
    //                 // Adiciona as séries atualizadas
    //                 for (String vehicleId : timeSeriesMap.keySet()) {
    //                     Map<ChartType, TimeSeries> vehicleData = timeSeriesMap.get(vehicleId);
    //                     TimeSeries series = vehicleData.get(type);
    //                     if (series != null) {
    //                         dataset.addSeries(series);
    //                     }
    //                 }
                    
    //                 // Atualiza o título com a data/hora atual
    //                 chart.setTitle(type.getLabel() + " - Atualizado em: " + 
    //                         new SimpleDateFormat("HH:mm:ss").format(new Date()));
    //             }
    //         }
    //     });
    // }
    
    /**
     * Implementação do método da interface DrivingDataListener.
     * Chamado quando novos dados de condução são adicionados.
     * 
     * @param data Novos dados de condução
     */
    @Override
    public void onNewDrivingData(DrivingData data) {
        if (data == null) return;
        
        String vehicleId = data.getCarID();
        long timestamp = data.getTimeStamp();

        // Obtém ou cria o mapa de TimeSeries para ESTE veículo
        Map<ChartType, TimeSeries> vehicleSpecificSeriesMap = timeSeriesByVehicle.computeIfAbsent(
                vehicleId, k -> {
                    Map<ChartType, TimeSeries> newMap = new HashMap<>();
                    for (ChartType type : ChartType.values()) {
                        // A legenda da série individual agora inclui o ID do veículo
                        newMap.put(type, new TimeSeries(type.getLabel() + " (" + vehicleId + ")"));
                    }
                    return newMap;
                });

        // Adiciona/atualiza os dados nas TimeSeries específicas do veículo
        addOrUpdateDataPoint(vehicleSpecificSeriesMap.get(ChartType.SPEED), timestamp, data.getSpeed());
        addOrUpdateDataPoint(vehicleSpecificSeriesMap.get(ChartType.FUEL_CONSUMPTION), timestamp, data.getFuelConsumption());
        addOrUpdateDataPoint(vehicleSpecificSeriesMap.get(ChartType.CO2_EMISSION), timestamp, data.getCo2Emission());
        addOrUpdateDataPoint(vehicleSpecificSeriesMap.get(ChartType.DISTANCE), timestamp, data.getOdometer());
        addOrUpdateDataPoint(vehicleSpecificSeriesMap.get(ChartType.HC_EMISSION), timestamp, data.getHCEmission());
    }

    private void addOrUpdateDataPoint(TimeSeries series, long timestamp, double value) {
        if (series != null) {
            SwingUtilities.invokeLater(() -> { // Garante que a modificação do dataset ocorra na EDT
                series.addOrUpdate(new Millisecond(new Date(timestamp)), value);
                while (series.getItemCount() > 200) { // Limita o histórico por série
                    series.delete(0, 0);
                }
            });
        }
    }

    // Este método é chamado pelo scheduler para atualizar os displays
    private void updateAllChartDisplays() {
        SwingUtilities.invokeLater(() -> {
            for (ChartType type : ChartType.values()) {
                TimeSeriesCollection mainDataset = chartDatasets.get(type);
                JFrame frame = chartFrames.get(type);

                if (mainDataset != null && frame != null && frame.isVisible()) {
                    mainDataset.removeAllSeries(); // Limpa o dataset principal
                    
                    // Readiciona todas as séries de todos os veículos para este tipo de gráfico
                    for (String vehicleId : timeSeriesByVehicle.keySet()) {
                        Map<ChartType, TimeSeries> vehicleData = timeSeriesByVehicle.get(vehicleId);
                        if (vehicleData != null) {
                            TimeSeries seriesForThisVehicleAndType = vehicleData.get(type);
                            if (seriesForThisVehicleAndType != null) {
                                mainDataset.addSeries(seriesForThisVehicleAndType);
                            }
                        }
                    }
                    // Atualiza o subtítulo do gráfico com a hora
                     JFreeChart chart = ((ChartPanel)frame.getContentPane()).getChart();
                     TextTitle subtitle = new TextTitle("Dados de todos os veículos - Atualizado em: " + new SimpleDateFormat("HH:mm:ss").format(new Date()));
                     subtitle.setFont(new Font("SansSerif", Font.PLAIN, 10));
                     chart.clearSubtitles(); // Limpa subtítulos antigos
                     chart.addSubtitle(subtitle); // Adiciona o novo

                    // O gráfico deve se redesenhar automaticamente, mas um repaint pode ser forçado se necessário
                    // frame.repaint();
                }
            }
        });
    }

        /**
     * Salva todos os gráficos atuais como arquivos PNG.
     * 
     * @return Array com os caminhos dos arquivos salvos
     * @throws IOException Se ocorrer um erro ao salvar os gráficos
     */
    
    public String[] saveAllCharts() throws IOException {
        // ... (lógica de saveAllCharts, agora salvando cada gráfico agregado em sua janela) ...
        // Esta parte precisará ser adaptada para iterar sobre chartFrames ou chartDatasets
        // e salvar cada JFreeChart.
        List<String> savedFilePaths = new ArrayList<>();
        File baseOutputDir = new File(chartDirectory);
        if (!baseOutputDir.exists()) {
            if (!baseOutputDir.mkdirs()) {
                throw new IOException("Não foi possível criar o diretório base para gráficos: " + baseOutputDir.getAbsolutePath());
            }
        }

        for (ChartType type : ChartType.values()) {
            JFrame frame = chartFrames.get(type);
            if (frame != null && frame.getContentPane() instanceof ChartPanel) {
                ChartPanel chartPanel = (ChartPanel) frame.getContentPane();
                JFreeChart chart = chartPanel.getChart();
                
                String fileName = type.name().toLowerCase() + "_aggregated_" + 
                                  dateFormat.format(new Date()) + ".png";
                File chartFile = new File(baseOutputDir, fileName);
                
                try {
                    ChartUtils.saveChartAsPNG(chartFile, chart, 800, 600);
                    savedFilePaths.add(chartFile.getAbsolutePath());
                    logger.info("Gráfico agregado salvo: " + chartFile.getAbsolutePath());
                } catch (IOException e) {
                    logger.log(Level.SEVERE, "Erro ao salvar gráfico agregado " + chartFile.getName() + ": " + e.getMessage(), e);
                }
            }
        }
        return savedFilePaths.toArray(new String[0]);
    }

    public void shutdown() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        SwingUtilities.invokeLater(() -> {
            for (JFrame frame : chartFrames.values()) {
                if (frame != null) {
                    frame.dispose();
                }
            }
            chartFrames.clear();
        });
        logger.info("RealTimeChartManager encerrado.");
    }
    
    /**
     * Atualiza uma série temporal com um novo valor.
     * 
     * @param seriesMap Mapa de séries temporais
     * @param type Tipo de dado
     * @param timestamp Timestamp do dado
     * @param value Valor a ser adicionado
     */
    private void updateTimeSeries(Map<ChartType, TimeSeries> seriesMap, ChartType type, 
                                 long timestamp, double value) {
        // Obtém ou cria a série temporal
        TimeSeries series = seriesMap.computeIfAbsent(type, k -> 
                new TimeSeries(type.getLabel()));
        
        // Adiciona o novo ponto à série
        series.addOrUpdate(new Millisecond(new Date(timestamp)), value);
        
        // Limita o número de pontos para evitar consumo excessivo de memória
        while (series.getItemCount() > 1000) {
            series.delete(0, 0);
        }
    }
    
    /**
     * Cria um painel com múltiplos gráficos para um veículo específico.
     * 
     * @param vehicleId ID do veículo
     * @return Painel com gráficos
     */
    public JPanel createVehicleDashboard(String vehicleId) {
        JPanel dashboard = new JPanel();
        dashboard.setLayout(new java.awt.GridLayout(3, 2, 10, 10));
        
        Map<ChartType, TimeSeries> vehicleSeries = timeSeriesMap.get(vehicleId);
        if (vehicleSeries == null) {
            return dashboard;
        }
        
        for (ChartType type : ChartType.values()) {
            TimeSeries series = vehicleSeries.get(type);
            if (series != null) {
                TimeSeriesCollection dataset = new TimeSeriesCollection();
                dataset.addSeries(series);
                
                JFreeChart chart = ChartFactory.createTimeSeriesChart(
                        vehicleId + " - " + type.getLabel(),
                        "Tempo",
                        type.getLabel(),
                        dataset,
                        true,
                        true,
                        false
                );
                
                // Personaliza o gráfico
                XYPlot plot = (XYPlot) chart.getPlot();
                plot.setBackgroundPaint(new Color(245, 245, 245));
                
                DateAxis dateAxis = (DateAxis) plot.getDomainAxis();
                dateAxis.setDateFormatOverride(new SimpleDateFormat("HH:mm:ss"));
                
                ChartPanel chartPanel = new ChartPanel(chart);
                chartPanel.setPreferredSize(new Dimension(400, 250));
                dashboard.add(chartPanel);
            }
        }
        
        return dashboard;
    }
    
    /**
     * Cria uma janela separada para exibir o dashboard de um veículo específico.
     * 
     * @param vehicleId ID do veículo
     */
    public void showVehicleDashboard(String vehicleId) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Dashboard - " + vehicleId);
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.setSize(1000, 800);
            
            JPanel dashboard = createVehicleDashboard(vehicleId);
            frame.add(dashboard);
            
            frame.setVisible(true);
        });
    }
}
