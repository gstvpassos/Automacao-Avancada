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
import java.util.logging.Level;
import java.util.logging.Logger;

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
import org.python.modules.binascii;

import io.sim.DrivingData;

/**
 * Classe responsável por gerar relatórios em Excel a partir dos dados de condução.
 * Implementa o padrão Singleton para garantir uma única instância de gerenciamento de relatórios.
 */
public class ExcelReportGenerator {
    
    private static final Logger logger = Logger.getLogger(ExcelReportGenerator.class.getName());
    private static ExcelReportGenerator instance;
    
    // Formato de data para nomes de arquivos
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");
    
    // Diretório para salvar relatórios
    private String reportDirectory = "/home/ubuntu/reporting/";
    
    /**
     * Construtor privado para implementar o padrão Singleton.
     */
    private ExcelReportGenerator() {
        logger.info("ExcelReportGenerator inicializado");
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
        logger.info("Diretório de relatórios definido para: " + this.reportDirectory);
    }
    
    /**
     * Gera um relatório Excel para um veículo específico.
     * AGORA RECEBE OS DADOS COMO PARÂMETRO.
     * @param vehicleId ID do veículo
     * @param data Lista de DrivingData para este veículo
     * @return Caminho do arquivo gerado
     * @throws IOException Se ocorrer um erro ao gerar o relatório
     */
    public String generateReportForVehicle(String vehicleId, List<DrivingData> data) throws IOException {
        if (data == null || data.isEmpty()) {
            throw new IllegalArgumentException("Não há dados disponíveis para o veículo: " + vehicleId);
        }
        
        String fileName = reportDirectory + vehicleId + "_" + dateFormat.format(new Date()) + ".xlsx";
        logger.info("Gerando relatório Excel para o veículo " + vehicleId + " em: " + fileName);
        
        try (Workbook workbook = new XSSFWorkbook()) {
            // Cria a planilha de dados detalhados
            Sheet sheet = workbook.createSheet(vehicleId);
            
            // Cria estilos para o cabeçalho
            CellStyle headerStyle = createHeaderStyle(workbook);
            
            // Cria o cabeçalho
            Row headerRow = sheet.createRow(0);
            String[] headers = {
                "Timestamp", "ID do Veículo", "Velocidade (m/s)", "Odômetro (m)",
                "Combustível (L)", "Consumo (mg)", "Emissão CO2 (mg)", "Longitude", "Latitude"
            };
            
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(i, 4000);
            }
            
            // Preenche os dados
            int rowNum = 1;
            for (DrivingData item : data) {
                Row row = sheet.createRow(rowNum++);
                
                row.createCell(0).setCellValue(new Date(item.getTimeStamp()).toString());
                row.createCell(1).setCellValue(item.getCarID());
                row.createCell(2).setCellValue(item.getSpeed());
                row.createCell(3).setCellValue(item.getOdometer());
                row.createCell(4).setCellValue(item.getFuelConsumption());
                row.createCell(5).setCellValue(item.getFuelConsumption());
                row.createCell(6).setCellValue(item.getCo2Emission());
                row.createCell(7).setCellValue(item.getLatLon()[0]);
                row.createCell(8).setCellValue(item.getLatLon()[1]);
            }
            
            // Cria a planilha de resumo
            createSummarySheet(workbook, data, vehicleId);
            
            // Salva o workbook em um arquivo
            try (FileOutputStream fileOut = new FileOutputStream(fileName)) {
                workbook.write(fileOut);
                logger.info("Relatório Excel para o veículo " + vehicleId + " gerado com sucesso em: " + fileName);
            } catch (IOException e) {
                logger.log(Level.SEVERE, "Erro ao salvar relatório Excel para o veículo " + vehicleId + ": " + e.getMessage(), e);
                throw e;
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Erro ao gerar relatório Excel para o veículo " + vehicleId + ": " + e.getMessage(), e);
            throw new IOException("Erro ao gerar relatório Excel: " + e.getMessage(), e);
        }
        
        return fileName;
    }
    
    /**
     * Gera um relatório Excel consolidado para todos os veículos.
     * AGORA RECEBE OS DADOS COMO PARÂMETRO.
     * @param allData Mapa de dados de condução por veículo, chave: vehicleId, valor: List<DrivingData>
     * @return Caminho do arquivo gerado
     * @throws IOException Se ocorrer um erro ao gerar o relatório
     */
    public String generateConsolidatedReport(Map<String, ArrayList<DrivingData>> allData) throws IOException {
        if (allData == null || allData.isEmpty()) {
            throw new IllegalArgumentException("Não há dados disponíveis (recebidos) para gerar o relatório consolidado");
        }
        
        String fileName = reportDirectory + "consolidated_report_" + dateFormat.format(new Date()) + ".xlsx";
        logger.info("Gerando relatório Excel consolidado em: " + fileName + " com dados de " + allData.size() + " veículos");
        
        try (Workbook workbook = new XSSFWorkbook()) {
            // Cria a planilha de resumo consolidado
            createConsolidatedSummarySheet(workbook, allData);
            
            // Para cada veículo, cria uma planilha de dados detalhados
            for (Map.Entry<String, ArrayList<DrivingData>> entry : allData.entrySet()) {
                String vehicleId = entry.getKey();
                ArrayList<DrivingData> vehicleData = entry.getValue();
                
                if (vehicleData.isEmpty()) {
                    continue;
                }
                
                // Cria a planilha de dados detalhados
                Sheet sheet = workbook.createSheet(vehicleId);
                
                // Cria estilos para o cabeçalho
                CellStyle headerStyle = createHeaderStyle(workbook);
                
                // Cria o cabeçalho
                Row headerRow = sheet.createRow(0);
                String[] headers = {
                    "Timestamp", "ID do Veículo", "Velocidade (m/s)", "Odômetro (m)",
                    "Combustível (L)", "Consumo (mg)", "Emissão CO2 (mg)", "Longitude", "Latitude"
                };
                
                for (int i = 0; i < headers.length; i++) {
                    Cell cell = headerRow.createCell(i);
                    cell.setCellValue(headers[i]);
                    cell.setCellStyle(headerStyle);
                    sheet.setColumnWidth(i, 4000);
                }
                
                // Preenche os dados
                int rowNum = 1;
                for (DrivingData item : vehicleData) {
                    Row row = sheet.createRow(rowNum++);
                    
                    row.createCell(0).setCellValue(new Date(item.getTimeStamp()).toString());
                    row.createCell(1).setCellValue(item.getCarID());
                    row.createCell(2).setCellValue(item.getSpeed());
                    row.createCell(3).setCellValue(item.getOdometer());
                    row.createCell(4).setCellValue(item.getFuelConsumption());
                    row.createCell(5).setCellValue(item.getFuelConsumption());
                    row.createCell(6).setCellValue(item.getCo2Emission());
                    row.createCell(7).setCellValue(item.getLatLon()[0]);
                    row.createCell(8).setCellValue(item.getLatLon()[1]);
                }
                
                // Cria a planilha de resumo para o veículo
                createSummarySheet(workbook, vehicleData, vehicleId);
            }
            
            // Salva o workbook em um arquivo
            try (FileOutputStream fileOut = new FileOutputStream(fileName)) {
                workbook.write(fileOut);
                logger.info("Relatório Excel consolidado gerado com sucesso em: " + fileName);
            } catch (IOException e) {
                logger.log(Level.SEVERE, "Erro ao salvar relatório Excel consolidado: " + e.getMessage(), e);
                throw e;
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Erro ao gerar relatório Excel consolidado: " + e.getMessage(), e);
            throw new IOException("Erro ao gerar relatório Excel consolidado: " + e.getMessage(), e);
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
        
        String vehicleId = data.isEmpty() ? "N/A" : data.get(0).getCarID();
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
    private void createConsolidatedSummarySheet(Workbook workbook, Map<String, ? extends List<DrivingData>> allData) {
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
        for (Map.Entry<String, ? extends List<DrivingData>> entry : allData.entrySet()) {
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
