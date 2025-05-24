package io.sim;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Classe que representa uma rota no sistema de simulação.
 * Contém informações sobre o percurso, status e métricas associadas.
 */
public class Rota implements Serializable {

    private static final long serialVersionUID = 1L;

    // Atributos básicos da rota
    private boolean on;
    private String uriRotaXML;
    private String[] rota;
    private String idRota;
    
    // Atributos adicionais para gerenciamento pela MobilityCompany
    private RotaStatus status;
    private String driverId;
    private String carId;
    private long creationTime;
    private long startTime;
    private long completionTime;
    private List<DrivingData> drivingDataList;
    
    /**
     * Enumeração para representar o status da rota.
     */
    public enum RotaStatus {
        CREATED,        // Rota criada, mas não atribuída
        ASSIGNED,       // Rota atribuída a um motorista/carro
        IN_PROGRESS,    // Rota em execução
        COMPLETED,      // Rota concluída com sucesso
        CANCELLED,      // Rota cancelada
        FAILED          // Falha na execução da rota
    }

    /**
     * Construtor para criar uma rota a partir de um arquivo XML e um ID de rota.
     * 
     * @param _uriRotasXML Caminho do arquivo XML que contém a definição da rota
     * @param _idRota ID da rota a ser carregada
     */
    public Rota(String _uriRotasXML, String _idRota) {
        this.uriRotaXML = _uriRotasXML;
        this.idRota = _idRota;
        this.status = RotaStatus.CREATED;
        this.creationTime = System.currentTimeMillis();
        this.drivingDataList = new ArrayList<>();
        
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(this.uriRotaXML);
            NodeList nList = doc.getElementsByTagName("vehicle");
            for (int i = 0; i < nList.getLength(); i++) {
                Node nNode = nList.item(i);
                if (nNode.getNodeType() == Node.ELEMENT_NODE) {
                    Element elem = (Element) nNode;
                    String idRotaAux = this.idRota;
                    Node node = elem.getElementsByTagName("route").item(0);
                    if (node == null) {
                        // Tenta com "rota" se "route" não existir
                        node = elem.getElementsByTagName("rota").item(0);
                    }
                    
                    if (node != null) {
                        Element edges = (Element) node;
                        this.rota = new String[] { idRotaAux, edges.getAttribute("edges") };
                    } else {
                        System.err.println("Elemento 'route' ou 'rota' não encontrado para o veículo " + idRotaAux);
                    }
                }
            }

            Thread.sleep(100);
            this.on = true;

        } catch (SAXException e) {
            System.err.println("Erro de SAX ao carregar rota: " + e.getMessage());
            e.printStackTrace();
        } catch (IOException e) {
            System.err.println("Erro de IO ao carregar rota: " + e.getMessage());
            e.printStackTrace();
        } catch (ParserConfigurationException e) {
            System.err.println("Erro de configuração do parser ao carregar rota: " + e.getMessage());
            e.printStackTrace();
        } catch (InterruptedException e) {
            System.err.println("Erro de interrupção ao carregar rota: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Atribui a rota a um motorista e carro específicos.
     * 
     * @param driverId ID do motorista
     * @param carId ID do carro
     * @return true se a atribuição foi bem-sucedida, false caso contrário
     */
    public boolean assignRota(String driverId, String carId) {
        if (this.status != RotaStatus.CREATED || !this.on) {
            return false;
        }
        
        this.driverId = driverId;
        this.carId = carId;
        this.status = RotaStatus.ASSIGNED;
        return true;
    }
    
    /**
     * Inicia a execução da rota.
     * 
     * @return true se o início foi bem-sucedido, false caso contrário
     */
    public boolean startRota() {
        if (this.status != RotaStatus.ASSIGNED || !this.on) {
            return false;
        }
        
        this.status = RotaStatus.IN_PROGRESS;
        this.startTime = System.currentTimeMillis();
        return true;
    }
    
    /**
     * Marca a rota como concluída.
     * 
     * @return true se a conclusão foi bem-sucedida, false caso contrário
     */
    public boolean completeRota() {
        if (this.status != RotaStatus.IN_PROGRESS) {
            return false;
        }
        
        this.status = RotaStatus.COMPLETED;
        this.completionTime = System.currentTimeMillis();
        return true;
    }
    
    /**
     * Cancela a rota.
     * 
     * @return true se o cancelamento foi bem-sucedido, false caso contrário
     */
    public boolean cancelRota() {
        if (this.status == RotaStatus.COMPLETED || this.status == RotaStatus.CANCELLED || this.status == RotaStatus.FAILED) {
            return false;
        }
        
        this.status = RotaStatus.CANCELLED;
        return true;
    }
    
    /**
     * Marca a rota como falha.
     * 
     * @param reason Motivo da falha
     * @return true se a marcação foi bem-sucedida, false caso contrário
     */
    public boolean failRota(String reason) {
        if (this.status == RotaStatus.COMPLETED || this.status == RotaStatus.CANCELLED || this.status == RotaStatus.FAILED) {
            return false;
        }
        
        this.status = RotaStatus.FAILED;
        // Aqui poderia registrar o motivo da falha em um atributo adicional
        return true;
    }
    
    /**
     * Adiciona dados de condução à rota.
     * 
     * @param drivingData Dados de condução a serem adicionados
     */
    public void addDrivingData(DrivingData drivingData) {
        if (this.status == RotaStatus.IN_PROGRESS) {
            this.drivingDataList.add(drivingData);
        }
    }
    
    /**
     * Calcula o tempo real de execução da rota em segundos.
     * 
     * @return Tempo de execução em segundos, ou -1 se a rota não foi concluída
     */
    public double getActualExecutionTime() {
        if (this.status != RotaStatus.COMPLETED) {
            return -1;
        }
        
        return (this.completionTime - this.startTime) / 1000.0;
    }
    
    /**
     * Calcula o consumo total de combustível durante a rota.
     * 
     * @return Consumo total de combustível, ou -1 se não houver dados suficientes
     */
    public double getTotalFuelConsumption() {
        if (this.drivingDataList.isEmpty()) {
            return -1;
        }
        
        double totalConsumption = 0;
        for (DrivingData data : this.drivingDataList) {
            totalConsumption += data.getFuelConsumption();
        }
        
        return totalConsumption;
    }
    
    /**
     * Calcula a emissão total de CO2 durante a rota.
     * 
     * @return Emissão total de CO2, ou -1 se não houver dados suficientes
     */
    public double getTotalCO2Emission() {
        if (this.drivingDataList.isEmpty()) {
            return -1;
        }
        
        double totalEmission = 0;
        for (DrivingData data : this.drivingDataList) {
            totalEmission += data.getCo2Emission();
        }
        
        return totalEmission;
    }
    
    /**
     * Calcula a distância real percorrida durante a rota.
     * 
     * @return Distância percorrida, ou -1 se não houver dados suficientes
     */
    public double getActualDistance() {
        if (this.drivingDataList.isEmpty()) {
            return -1;
        }
        
        // Assume que o último relatório contém a distância total percorrida
        return this.drivingDataList.get(this.drivingDataList.size() - 1).getOdometer();
    }
    
    /**
     * Calcula a velocidade média durante a rota.
     * 
     * @return Velocidade média, ou -1 se não houver dados suficientes
     */
    public double getAverageSpeed() {
        if (this.drivingDataList.isEmpty()) {
            return -1;
        }
        
        double totalSpeed = 0;
        for (DrivingData data : this.drivingDataList) {
            totalSpeed += data.getSpeed();
        }
        
        return totalSpeed / this.drivingDataList.size();
    }
    
    /**
     * Gera um relatório resumido da rota.
     * 
     * @return String contendo um resumo da rota
     */
    public String generateSummary() {
        StringBuilder summary = new StringBuilder();
        summary.append("Resumo da Rota: ").append(this.idRota).append("\n");
        summary.append("Status: ").append(this.status).append("\n");
        summary.append("Ativa: ").append(this.on ? "Sim" : "Não").append("\n");
        
        if (this.rota != null && this.rota.length > 1) {
            summary.append("Edges: ").append(this.rota[1]).append("\n");
        }
        
        summary.append("Motorista: ").append(this.driverId != null ? this.driverId : "Não atribuído").append("\n");
        summary.append("Carro: ").append(this.carId != null ? this.carId : "Não atribuído").append("\n");
        summary.append("Criada em: ").append(new java.util.Date(this.creationTime)).append("\n");
        
        if (this.startTime > 0) {
            summary.append("Iniciada em: ").append(new java.util.Date(this.startTime)).append("\n");
        }
        
        if (this.completionTime > 0) {
            summary.append("Concluída em: ").append(new java.util.Date(this.completionTime)).append("\n");
            summary.append("Tempo de execução: ").append(getActualExecutionTime()).append(" segundos\n");
        }
        
        if (!this.drivingDataList.isEmpty()) {
            summary.append("Distância percorrida: ").append(getActualDistance()).append(" metros\n");
            summary.append("Velocidade média: ").append(getAverageSpeed()).append(" m/s\n");
            summary.append("Consumo total de combustível: ").append(getTotalFuelConsumption()).append("\n");
            summary.append("Emissão total de CO2: ").append(getTotalCO2Emission()).append("\n");
        }
        
        return summary.toString();
    }
    
    /**
     * Obtém a lista de edges da rota.
     * 
     * @return Lista de edges da rota
     */
    public List<String> getEdgesList() {
        if (this.rota != null && this.rota.length > 1) {
            return Arrays.asList(this.rota[1].split(" "));
        }
        return new ArrayList<>();
    }
    
    /**
     * Obtém o ID da rota.
     * 
     * @return ID da rota
     */
    public String getIDRota() {
        return this.idRota;
    }

    /**
     * Obtém o caminho do arquivo XML.
     * 
     * @return Caminho do arquivo XML
     */
    public String getUriRotaXML() {
        return this.uriRotaXML;
    }

    /**
     * Obtém o array de rota.
     * 
     * @return Array de rota
     */
    public String[] getRota() {
        return this.rota;
    }

    /**
     * Obtém o ID da rota (método alternativo).
     * 
     * @return ID da rota
     */
    public String getIdRota() {
        return this.idRota;
    }

    /**
     * Verifica se a rota está ativa.
     * 
     * @return true se a rota estiver ativa, false caso contrário
     */
    public boolean isOn() {
        return this.on;
    }
    
    /**
     * Obtém o status atual da rota.
     * 
     * @return Status da rota
     */
    public RotaStatus getStatus() {
        return status;
    }
    
    /**
     * Obtém o ID do motorista atribuído à rota.
     * 
     * @return ID do motorista
     */
    public String getDriverId() {
        return driverId;
    }
    
    /**
     * Obtém o ID do carro atribuído à rota.
     * 
     * @return ID do carro
     */
    public String getCarId() {
        return carId;
    }
    
    /**
     * Obtém o timestamp de criação da rota.
     * 
     * @return Timestamp de criação
     */
    public long getCreationTime() {
        return creationTime;
    }
    
    /**
     * Obtém o timestamp de início da rota.
     * 
     * @return Timestamp de início
     */
    public long getStartTime() {
        return startTime;
    }
    
    /**
     * Obtém o timestamp de conclusão da rota.
     * 
     * @return Timestamp de conclusão
     */
    public long getCompletionTime() {
        return completionTime;
    }
    
    /**
     * Obtém a lista de dados de condução da rota.
     * 
     * @return Lista de dados de condução
     */
    public List<DrivingData> getDrivingDataList() {
        return new ArrayList<>(drivingDataList);
    }
    
    /**
     * Retorna uma representação em string da rota.
     * 
     * @return Representação em string da rota
     */
    @Override
    public String toString() {
        return "Rota [id=" + idRota + ", status=" + status + ", on=" + on + "]";
    }
}
