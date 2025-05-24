package io.sim.reporting;

import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import io.sim.DrivingData;

/**
 * Classe responsável por gerar relatórios em Excel a partir dos dados de condução.
 * Implementa o padrão Singleton para garantir uma única instância de gerenciamento de relatórios.
 */
public class ExcelReportGenerator {
    
    private static ExcelReportGenerator instance;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    
    // Armazena dados de condução por veículo
    private final Map<String, List<DrivingData>> drivingDataByVehicle;
    
    // Armazena listeners para notificação de novos dados
    private final List<DrivingDataListener> listeners;
    
    // Formato de data para nomes de arquivos
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");
    
    // Diretório para salvar relatórios
    private String reportDirectory = "/home/ubuntu/reporting/";
    
    /**
     * Construtor privado para implementar o padrão Singleton.
     */
    private ExcelReportGenerator() {
        drivingDataByVehicle = new ConcurrentHashMap<>();
        listeners = new CopyOnWriteArrayList<>();
    }
    
    /**
     * Obtém a instância única do gerador de relatórios.
     * 
     * @return Instância do ExcelReportGenerator
     */
    public static synchronized ExcelReportGenerator getInstance() {
        if (instance == null) {
            instance = new ExcelReportGenerator();
        }
        return instance;
    }
    
    /**
     * Define o diretório onde os relatórios serão salvos.
     * 
     * @param directory Caminho do diretório
     */
    public void setReportDirectory(String directory) {
        if (!directory.endsWith("/")) {
            directory += "/";
        }
        this.reportDirectory = directory;
    }
    
    /**
     * Adiciona dados de condução para um veículo específico.
     * 
     * @param data Dados de condução a serem adicionados
     */
    public void addDrivingData(DrivingData data) {
        if (data == null) {
            return;
        }
        
        String vehicleId = data.getAutoID();
        
        lock.writeLock().lock();
        try {
            // Obtém ou cria a lista de dados para o veículo
            List<DrivingData> vehicleData = drivingDataByVehicle.computeIfAbsent(
                    vehicleId, k -> new ArrayList<>());
            
            // Adiciona os novos dados
            vehicleData.add(data);
            
            // Notifica os listeners sobre os novos dados
            notifyListeners(data);
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Adiciona uma lista de dados de condução.
     * 
     * @param dataList Lista de dados de condução
     */
    public void addDrivingDataBatch(List<DrivingData> dataList) {
        if (dataList == null || dataList.isEmpty()) {
            return;
        }
        
        lock.writeLock().lock();
        try {
            for (DrivingData data : dataList) {
                if (data != null) {
                    String vehicleId = data.getAutoID();
                    
                    // Obtém ou cria a lista de dados para o veículo
                    List<DrivingData> vehicleData = drivingDataByVehicle.computeIfAbsent(
                            vehicleId, k -> new ArrayList<>());
                    
                    // Adiciona os novos dados
                    vehicleData.add(data);
                    
                    // Notifica os listeners sobre os novos dados
                    notifyListeners(data);
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Obtém todos os dados de condução para um veículo específico.
     * 
     * @param vehicleId ID do veículo
     * @return Lista de dados de condução
     */
    public List<DrivingData> getDrivingDataForVehicle(String vehicleId) {
        lock.readLock().lock();
        try {
            List<DrivingData> data = drivingDataByVehicle.get(vehicleId);
            if (data == null) {
                return new ArrayList<>();
            }
            return new ArrayList<>(data);
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Obtém todos os dados de condução para todos os veículos.
     * 
     * @return Mapa de dados de condução por veículo
     */
    public Map<String, List<DrivingData>> getAllDrivingData() {
        lock.readLock().lock();
        try {
            Map<String, List<DrivingData>> result = new HashMap<>();
            for (Map.Entry<String, List<DrivingData>> entry : drivingDataByVehicle.entrySet()) {
                result.put(entry.getKey(), new ArrayList<>(entry.getValue()));
            }
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Limpa todos os dados de condução armazenados.
     */
    public void clearAllData() {
        lock.writeLock().lock();
        try {
            drivingDataByVehicle.clear();
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Limpa os dados de condução para um veículo específico.
     * 
     * @param vehicleId ID do veículo
     */
    public void clearDataForVehicle(String vehicleId) {
        lock.writeLock().lock();
        try {
            drivingDataByVehicle.remove(vehicleId);
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Adiciona um listener para ser notificado sobre novos dados.
     * 
     * @param listener Listener a ser adicionado
     */
    public void addDrivingDataListener(DrivingDataListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }
    
    /**
     * Remove um listener.
     * 
     * @param listener Listener a ser removido
     */
    public void removeDrivingDataListener(DrivingDataListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }
    
    /**
     * Notifica todos os listeners sobre novos dados.
     * 
     * @param data Novos dados de condução
     */
    private void notifyListeners(DrivingData data) {
        for (DrivingDataListener listener : listeners) {
            listener.onNewDrivingData(data);
        }
    }
    
    /**
     * Gera um relatório Excel para um veículo específico.
     * 
     * @param vehicleId ID do veículo
     * @return Caminho do arquivo gerado
     * @throws IOException Se ocorrer um erro ao gerar o relatório
     */
    public String generateReportForVehicle(String vehicleId) throws IOException {
        List<DrivingData> data = getDrivingDataForVehicle(vehicleId);
        if (data.isEmpty()) {
            throw new IllegalArgumentException("Não há dados disponíveis para o veículo: " + vehicleId);
        }
        
        String fileName = reportDirectory + vehicleId + "_" + dateFormat.format(new Date()) + ".xlsx";
        
        try (Workbook workbook = new XSSFWorkbook()) {
            // Cria a planilha principal
            Sheet sheet = workbook.createSheet("Dados de Condução");
            
            // Cria estilos para o cabeçalho
            CellStyle headerStyle = createHeaderStyle(workbook);
            
            // Cria o cabeçalho
            Row headerRow = sheet.createRow(0);
            String[] headers = {
                "Timestamp", "ID do Carro", "ID do Motorista", "Posição X", "Posição Y", 
                "Latitude", "Longitude", "ID da Via", "ID da Rota", "Velocidade (m/s)", 
                "Odômetro (m)", "Consumo de Combustível (mg/s)", "Consumo Médio", 
                "Tipo de Combustível", "Preço do Combustível", "Emissão de CO2 (mg/s)",
                "Emissão de HC (mg/s)", "Capacidade de Pessoas", "Número de Pessoas"
            };
            
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(i, 4000); // Largura da coluna
            }
            
            // Preenche os dados
            int rowNum = 1;
            for (DrivingData item : data) {
                Row row = sheet.createRow(rowNum++);
                
                row.createCell(0).setCellValue(new Date(item.getTimeStamp()).toString());
                row.createCell(1).setCellValue(item.getAutoID());
                row.createCell(2).setCellValue(item.getDriverID());
                row.createCell(3).setCellValue(item.getX_Position());
                row.createCell(4).setCellValue(item.getY_Position());
                
                double[] latLon = item.getLatLon();
                row.createCell(5).setCellValue(latLon[0]); // Latitude
                row.createCell(6).setCellValue(latLon[1]); // Longitude
                
                row.createCell(7).setCellValue(item.getRoadIDSUMO());
                row.createCell(8).setCellValue(item.getRouteIDSUMO());
                row.createCell(9).setCellValue(item.getSpeed());
                row.createCell(10).setCellValue(item.getOdometer());
                row.createCell(11).setCellValue(item.getFuelConsumption());
                row.createCell(12).setCellValue(item.getAverageFuelConsumption());
                
                // Converte o tipo de combustível para texto
                String fuelTypeText;
                switch (item.getFuelType()) {
                    case 1: fuelTypeText = "Diesel"; break;
                    case 2: fuelTypeText = "Gasolina"; break;
                    case 3: fuelTypeText = "Etanol"; break;
                    case 4: fuelTypeText = "Híbrido"; break;
                    default: fuelTypeText = "Desconhecido";
                }
                row.createCell(13).setCellValue(fuelTypeText);
                
                row.createCell(14).setCellValue(item.getFuelPrice());
                row.createCell(15).setCellValue(item.getCo2Emission());
                row.createCell(16).setCellValue(item.getHCEmission());
                row.createCell(17).setCellValue(item.getPersonCapacity());
                row.createCell(18).setCellValue(item.getPersonNumber());
            }
            
            // Cria uma planilha de resumo
            createSummarySheet(workbook, data, vehicleId);
            
            // Salva o arquivo
            try (FileOutputStream fileOut = new FileOutputStream(fileName)) {
                workbook.write(fileOut);
            }
        }
        
        return fileName;
    }
    
    /**
     * Gera um relatório Excel consolidado para todos os veículos.
     * 
     * @return Caminho do arquivo gerado
     * @throws IOException Se ocorrer um erro ao gerar o relatório
     */
    public String generateConsolidatedReport() throws IOException {
        Map<String, List<DrivingData>> allData = getAllDrivingData();
        if (allData.isEmpty()) {
            throw new IllegalArgumentException("Não há dados disponíveis para gerar o relatório");
        }
        
        String fileName = reportDirectory + "consolidated_report_" + dateFormat.format(new Date()) + ".xlsx";
        
        try (Workbook workbook = new XSSFWorkbook()) {
            // Cria uma planilha para cada veículo
            for (Map.Entry<String, List<DrivingData>> entry : allData.entrySet()) {
                String vehicleId = entry.getKey();
                List<DrivingData> vehicleData = entry.getValue();
                
                if (vehicleData.isEmpty()) {
                    continue;
                }
                
                // Cria a planilha para o veículo
                Sheet sheet = workbook.createSheet(vehicleId);
                
                // Cria estilos para o cabeçalho
                CellStyle headerStyle = createHeaderStyle(workbook);
                
                // Cria o cabeçalho
                Row headerRow = sheet.createRow(0);
                String[] headers = {
                    "Timestamp", "ID do Carro", "ID do Motorista", "Posição X", "Posição Y", 
                    "Latitude", "Longitude", "ID da Via", "ID da Rota", "Velocidade (m/s)", 
                    "Odômetro (m)", "Consumo de Combustível (mg/s)", "Consumo Médio", 
                    "Tipo de Combustível", "Preço do Combustível", "Emissão de CO2 (mg/s)",
                    "Emissão de HC (mg/s)", "Capacidade de Pessoas", "Número de Pessoas"
                };
                
                for (int i = 0; i < headers.length; i++) {
                    Cell cell = headerRow.createCell(i);
                    cell.setCellValue(headers[i]);
                    cell.setCellStyle(headerStyle);
                    sheet.setColumnWidth(i, 4000); // Largura da coluna
                }
                
                // Preenche os dados
                int rowNum = 1;
                for (DrivingData item : vehicleData) {
                    Row row = sheet.createRow(rowNum++);
                    
                    row.createCell(0).setCellValue(new Date(item.getTimeStamp()).toString());
                    row.createCell(1).setCellValue(item.getAutoID());
                    row.createCell(2).setCellValue(item.getDriverID());
                    row.createCell(3).setCellValue(item.getX_Position());
                    row.createCell(4).setCellValue(item.getY_Position());
                    
                    double[] latLon = item.getLatLon();
                    row.createCell(5).setCellValue(latLon[0]); // Latitude
                    row.createCell(6).setCellValue(latLon[1]); // Longitude
                    
                    row.createCell(7).setCellValue(item.getRoadIDSUMO());
                    row.createCell(8).setCellValue(item.getRouteIDSUMO());
                    row.createCell(9).setCellValue(item.getSpeed());
                    row.createCell(10).setCellValue(item.getOdometer());
                    row.createCell(11).setCellValue(item.getFuelConsumption());
                    row.createCell(12).setCellValue(item.getAverageFuelConsumption());
                    
                    // Converte o tipo de combustível para texto
                    String fuelTypeText;
                    switch (item.getFuelType()) {
                        case 1: fuelTypeText = "Diesel"; break;
                        case 2: fuelTypeText = "Gasolina"; break;
                        case 3: fuelTypeText = "Etanol"; break;
                        case 4: fuelTypeText = "Híbrido"; break;
                        default: fuelTypeText = "Desconhecido";
                    }
                    row.createCell(13).setCellValue(fuelTypeText);
                    
                    row.createCell(14).setCellValue(item.getFuelPrice());
                    row.createCell(15).setCellValue(item.getCo2Emission());
                    row.createCell(16).setCellValue(item.getHCEmission());
                    row.createCell(17).setCellValue(item.getPersonCapacity());
                    row.createCell(18).setCellValue(item.getPersonNumber());
                }
                
                // Cria uma planilha de resumo para o veículo
                createSummarySheet(workbook, vehicleData, vehicleId + "_Summary");
            }
            
            // Cria uma planilha de resumo geral
            createConsolidatedSummarySheet(workbook, allData);
            
            // Salva o arquivo
            try (FileOutputStream fileOut = new FileOutputStream(fileName)) {
                workbook.write(fileOut);
            }
        }
        
        return fileName;
    }
    
    /**
     * Cria uma planilha de resumo para um veículo.
     * 
     * @param workbook Workbook do Excel
     * @param data Dados de condução
     * @param sheetName Nome da planilha
     */
    private void createSummarySheet(Workbook workbook, List<DrivingData> data, String sheetName) {
        Sheet sheet = workbook.createSheet(sheetName + "_Resumo");
        
        // Cria estilos para o cabeçalho
        CellStyle headerStyle = createHeaderStyle(workbook);
        
        // Cria estilos para os valores
        CellStyle valueStyle = workbook.createCellStyle();
        valueStyle.setBorderBottom(BorderStyle.THIN);
        valueStyle.setBorderTop(BorderStyle.THIN);
        valueStyle.setBorderLeft(BorderStyle.THIN);
        valueStyle.setBorderRight(BorderStyle.THIN);
        
        // Calcula estatísticas
        double totalDistance = 0;
        double totalFuelConsumption = 0;
        double maxSpeed = 0;
        double avgSpeed = 0;
        double totalCO2Emission = 0;
        
        for (DrivingData item : data) {
            totalDistance = Math.max(totalDistance, item.getOdometer());
            totalFuelConsumption += item.getFuelConsumption();
            maxSpeed = Math.max(maxSpeed, item.getSpeed());
            avgSpeed += item.getSpeed();
            totalCO2Emission += item.getCo2Emission();
        }
        
        avgSpeed = data.isEmpty() ? 0 : avgSpeed / data.size();
        
        // Cria o cabeçalho
        Row headerRow = sheet.createRow(0);
        Cell headerCell = headerRow.createCell(0);
        headerCell.setCellValue("Resumo da Viagem");
        headerCell.setCellStyle(headerStyle);
        
        // Mescla células para o título
        sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(0, 0, 0, 1));
        
        // Adiciona os dados de resumo
        String[] labels = {
            "ID do Veículo", "Distância Total (m)", "Consumo Total de Combustível (mg)",
            "Velocidade Máxima (m/s)", "Velocidade Média (m/s)", "Emissão Total de CO2 (mg)",
            "Número de Registros", "Primeiro Registro", "Último Registro"
        };
        
        String vehicleId = data.isEmpty() ? "N/A" : data.get(0).getAutoID();
        String firstTimestamp = data.isEmpty() ? "N/A" : new Date(data.get(0).getTimeStamp()).toString();
        String lastTimestamp = data.isEmpty() ? "N/A" : new Date(data.get(data.size() - 1).getTimeStamp()).toString();
        
        Object[] values = {
            vehicleId, totalDistance, totalFuelConsumption,
            maxSpeed, avgSpeed, totalCO2Emission,
            data.size(), firstTimestamp, lastTimestamp
        };
        
        for (int i = 0; i < labels.length; i++) {
            Row row = sheet.createRow(i + 1);
            
            Cell labelCell = row.createCell(0);
            labelCell.setCellValue(labels[i]);
            labelCell.setCellStyle(headerStyle);
            
            Cell valueCell = row.createCell(1);
            if (values[i] instanceof Number) {
                valueCell.setCellValue(((Number) values[i]).doubleValue());
            } else {
                valueCell.setCellValue(values[i].toString());
            }
            valueCell.setCellStyle(valueStyle);
        }
        
        // Ajusta a largura das colunas
        sheet.setColumnWidth(0, 8000);
        sheet.setColumnWidth(1, 8000);
    }
    
    /**
     * Cria uma planilha de resumo consolidado para todos os veículos.
     * 
     * @param workbook Workbook do Excel
     * @param allData Dados de todos os veículos
     */
    private void createConsolidatedSummarySheet(Workbook workbook, Map<String, List<DrivingData>> allData) {
        Sheet sheet = workbook.createSheet("Resumo_Consolidado");
        
        // Cria estilos para o cabeçalho
        CellStyle headerStyle = createHeaderStyle(workbook);
        
        // Cria o cabeçalho
        Row headerRow = sheet.createRow(0);
        String[] headers = {
            "ID do Veículo", "Distância Total (m)", "Consumo Total (mg)", "Velocidade Máx (m/s)",
            "Velocidade Média (m/s)", "Emissão CO2 Total (mg)", "Registros", "Duração (s)"
        };
        
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
            sheet.setColumnWidth(i, 4000);
        }
        
        // Preenche os dados de resumo para cada veículo
        int rowNum = 1;
        for (Map.Entry<String, List<DrivingData>> entry : allData.entrySet()) {
            String vehicleId = entry.getKey();
            List<DrivingData> vehicleData = entry.getValue();
            
            if (vehicleData.isEmpty()) {
                continue;
            }
            
            Row row = sheet.createRow(rowNum++);
            
            // Calcula estatísticas
            double totalDistance = 0;
            double totalFuelConsumption = 0;
            double maxSpeed = 0;
            double avgSpeed = 0;
            double totalCO2Emission = 0;
            long startTime = vehicleData.get(0).getTimeStamp();
            long endTime = vehicleData.get(vehicleData.size() - 1).getTimeStamp();
            long duration = (endTime - startTime) / 1000; // em segundos
            
            for (DrivingData item : vehicleData) {
                totalDistance = Math.max(totalDistance, item.getOdometer());
                totalFuelConsumption += item.getFuelConsumption();
                maxSpeed = Math.max(maxSpeed, item.getSpeed());
                avgSpeed += item.getSpeed();
                totalCO2Emission += item.getCo2Emission();
            }
            
            avgSpeed = vehicleData.isEmpty() ? 0 : avgSpeed / vehicleData.size();
            
            // Preenche a linha com os dados
            row.createCell(0).setCellValue(vehicleId);
            row.createCell(1).setCellValue(totalDistance);
            row.createCell(2).setCellValue(totalFuelConsumption);
            row.createCell(3).setCellValue(maxSpeed);
            row.createCell(4).setCellValue(avgSpeed);
            row.createCell(5).setCellValue(totalCO2Emission);
            row.createCell(6).setCellValue(vehicleData.size());
            row.createCell(7).setCellValue(duration);
        }
    }
    
    /**
     * Cria um estilo para o cabeçalho da planilha.
     * 
     * @param workbook Workbook do Excel
     * @return Estilo para o cabeçalho
     */
    private CellStyle createHeaderStyle(Workbook workbook) {
        CellStyle headerStyle = workbook.createCellStyle();
        
        // Configura a fonte
        Font headerFont = workbook.createFont();
        headerFont.setBold(true);
        headerFont.setFontHeightInPoints((short) 12);
        headerStyle.setFont(headerFont);
        
        // Configura as bordas
        headerStyle.setBorderBottom(BorderStyle.MEDIUM);
        headerStyle.setBorderTop(BorderStyle.MEDIUM);
        headerStyle.setBorderLeft(BorderStyle.MEDIUM);
        headerStyle.setBorderRight(BorderStyle.MEDIUM);
        
        // Configura o preenchimento
        headerStyle.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
        headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        
        return headerStyle;
    }
    
    /**
     * Interface para notificação de novos dados de condução.
     */
    public interface DrivingDataListener {
        /**
         * Método chamado quando novos dados de condução são adicionados.
         * 
         * @param data Novos dados de condução
         */
        void onNewDrivingData(DrivingData data);
    }
}
