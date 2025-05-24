package io.sim.reporting;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
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
import org.jfree.data.xy.XYDataset;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;

import io.sim.DrivingData;

/**
 * Classe responsável por gerar gráficos em tempo real a partir dos dados de condução.
 * Implementa o padrão Singleton para garantir uma única instância de gerenciamento de gráficos.
 */
public class RealTimeChartManager implements ExcelReportGenerator.DrivingDataListener {
    
    private static RealTimeChartManager instance;
    
    // Armazena séries temporais por veículo e tipo de dado
    private final Map<String, Map<ChartType, TimeSeries>> timeSeriesMap;
    
    // Armazena os painéis de gráficos por tipo
    private final Map<ChartType, ChartPanel> chartPanels;
    
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
        timeSeriesMap = new ConcurrentHashMap<>();
        chartPanels = new ConcurrentHashMap<>();
        scheduler = Executors.newScheduledThreadPool(1);
        
        // Cria o diretório para salvar os gráficos
        File dir = new File(chartDirectory);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        
        // Registra-se como listener no ExcelReportGenerator
        ExcelReportGenerator.getInstance().addDrivingDataListener(this);
        
        // Agenda a atualização periódica dos gráficos
        scheduler.scheduleAtFixedRate(this::updateCharts, 1, 1, TimeUnit.SECONDS);
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
            mainFrame = new JFrame("Monitoramento em Tempo Real - SUMO Simulator");
            mainFrame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            mainFrame.setLayout(new java.awt.GridLayout(3, 2, 10, 10));
            mainFrame.setSize(1200, 800);
            
            // Inicializa os gráficos para cada tipo
            for (ChartType type : ChartType.values()) {
                JFreeChart chart = createChart(type);
                ChartPanel chartPanel = new ChartPanel(chart);
                chartPanel.setPreferredSize(new Dimension(500, 300));
                chartPanel.setMouseZoomable(true);
                chartPanel.setDomainZoomable(true);
                chartPanel.setRangeZoomable(true);
                
                chartPanels.put(type, chartPanel);
                mainFrame.add(chartPanel);
            }
            
            mainFrame.setVisible(true);
        });
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
    private void updateCharts() {
        SwingUtilities.invokeLater(() -> {
            for (ChartType type : ChartType.values()) {
                ChartPanel chartPanel = chartPanels.get(type);
                if (chartPanel != null) {
                    JFreeChart chart = chartPanel.getChart();
                    XYPlot plot = chart.getXYPlot();
                    TimeSeriesCollection dataset = (TimeSeriesCollection) plot.getDataset();
                    
                    // Limpa o dataset atual
                    dataset.removeAllSeries();
                    
                    // Adiciona as séries atualizadas
                    for (String vehicleId : timeSeriesMap.keySet()) {
                        Map<ChartType, TimeSeries> vehicleData = timeSeriesMap.get(vehicleId);
                        TimeSeries series = vehicleData.get(type);
                        if (series != null) {
                            dataset.addSeries(series);
                        }
                    }
                    
                    // Atualiza o título com a data/hora atual
                    chart.setTitle(type.getLabel() + " - Atualizado em: " + 
                            new SimpleDateFormat("HH:mm:ss").format(new Date()));
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
        String[] filePaths = new String[ChartType.values().length + 1]; // +1 para o gráfico de comparação
        int index = 0;
        
        // Salva os gráficos de série temporal
        for (ChartType type : ChartType.values()) {
            ChartPanel chartPanel = chartPanels.get(type);
            if (chartPanel != null) {
                String fileName = chartDirectory + type.name().toLowerCase() + "_" + 
                        dateFormat.format(new Date()) + ".png";
                
                ChartUtils.saveChartAsPNG(
                        new File(fileName),
                        chartPanel.getChart(),
                        800,  // largura
                        600   // altura
                );
                
                filePaths[index++] = fileName;
            }
        }
        
        // Salva um gráfico de comparação
        JFreeChart comparisonChart = createComparisonChart(ChartType.FUEL_CONSUMPTION);
        String comparisonFileName = chartDirectory + "comparison_" + 
                dateFormat.format(new Date()) + ".png";
        
        ChartUtils.saveChartAsPNG(
                new File(comparisonFileName),
                comparisonChart,
                800,  // largura
                600   // altura
        );
        
        filePaths[index] = comparisonFileName;
        
        return filePaths;
    }
    
    /**
     * Fecha a interface gráfica e libera recursos.
     */
    public void shutdown() {
        if (mainFrame != null) {
            mainFrame.dispose();
        }
        
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
        }
        
        // Remove-se como listener
        ExcelReportGenerator.getInstance().removeDrivingDataListener(this);
    }
    
    /**
     * Implementação do método da interface DrivingDataListener.
     * Chamado quando novos dados de condução são adicionados.
     * 
     * @param data Novos dados de condução
     */
    @Override
    public void onNewDrivingData(DrivingData data) {
        if (data == null) {
            return;
        }
        
        String vehicleId = data.getAutoID();
        long timestamp = data.getTimeStamp();
        
        // Obtém ou cria o mapa de séries para o veículo
        Map<ChartType, TimeSeries> vehicleSeries = timeSeriesMap.computeIfAbsent(
                vehicleId, k -> new HashMap<>());
        
        // Atualiza a série de velocidade
        updateTimeSeries(vehicleSeries, ChartType.SPEED, timestamp, data.getSpeed());
        
        // Atualiza a série de consumo de combustível
        updateTimeSeries(vehicleSeries, ChartType.FUEL_CONSUMPTION, timestamp, data.getFuelConsumption());
        
        // Atualiza a série de emissão de CO2
        updateTimeSeries(vehicleSeries, ChartType.CO2_EMISSION, timestamp, data.getCo2Emission());
        
        // Atualiza a série de distância percorrida
        updateTimeSeries(vehicleSeries, ChartType.DISTANCE, timestamp, data.getOdometer());
        
        // Atualiza a série de emissão de HC
        updateTimeSeries(vehicleSeries, ChartType.HC_EMISSION, timestamp, data.getHCEmission());
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
